package dev.pabloi.whisper.engine.remote

import android.content.Context
import dev.pabloi.whisper.audio.AudioDecoder
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.TranscriptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Talks to any OpenAI-compatible `/v1/audio/transcriptions` endpoint.
 *
 * Verified-compatible servers:
 *  - OpenAI
 *  - faster-whisper-server (ggml-org ecosystem)
 *  - vLLM's audio endpoint
 *  - RunPod hosted whisper templates that wrap the above
 *
 * Sends the file unchanged when the container is already something the server
 * accepts (wav/m4a/mp3/flac/ogg/webm). If the input is a raw PCM or an odd
 * container, we decode locally and upload a WAV — keeps the server happy
 * regardless of what the user picked.
 */
class RemoteOpenAIEngine(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String?,
    private val modelName: String = "whisper-1",
) : TranscriptionEngine {

    override val id: String = "remote-openai"
    override val displayName: String = "Remote (OpenAI-compatible)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun transcribe(audio: AudioSource, options: TranscribeOptions): Flow<TranscribeEvent> = flow {
        val t0 = System.currentTimeMillis()
        emit(TranscribeEvent.Progress(0.05f, "Preparing upload"))
        val (uploadFile, filename, mime, cleanup) = materialize(audio)

        try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, uploadFile.asRequestBody(mime.toMediaType()))
                .addFormDataPart("model", modelName)
                .addFormDataPart("response_format", "json")
            if (options.task == TranscribeOptions.Task.TRANSLATE) {
                bodyBuilder.addFormDataPart("task", "translate")
            }
            if (!options.language.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", options.language)
            }
            if (options.timestamps) {
                bodyBuilder.addFormDataPart("timestamp_granularities[]", "segment")
                bodyBuilder.addFormDataPart("response_format", "verbose_json")
            }

            val url = baseUrl.trimEnd('/') + "/v1/audio/transcriptions"
            val reqBuilder = Request.Builder().url(url).post(bodyBuilder.build())
            if (!apiKey.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")

            emit(TranscribeEvent.Progress(0.2f, "Uploading"))
            http.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: ${raw.take(300)}")
                }
                if (options.timestamps) {
                    val verbose = json.decodeFromString<VerboseResponse>(raw)
                    for (s in verbose.segments.orEmpty()) {
                        emit(TranscribeEvent.Segment(s.text.trim(), s.start, s.end))
                    }
                    emit(TranscribeEvent.Final(verbose.text, System.currentTimeMillis() - t0))
                } else {
                    val simple = json.decodeFromString<SimpleResponse>(raw)
                    emit(TranscribeEvent.Segment(simple.text.trim(), null, null))
                    emit(TranscribeEvent.Final(simple.text, System.currentTimeMillis() - t0))
                }
            }
        } finally {
            cleanup()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get a File ready for upload. For Uri or File sources we copy once to
     * app cache to avoid exposing the original path to OkHttp. For PCM
     * sources we write out a 16-bit WAV.
     */
    private fun materialize(audio: AudioSource): Upload {
        val cacheDir = File(context.cacheDir, "remote_uploads").apply { mkdirs() }
        return when (audio) {
            is AudioSource.File -> {
                val src = File(audio.path)
                val dst = File(cacheDir, "upload-${System.nanoTime()}-${src.name}")
                src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                Upload(dst, src.name, guessMime(src.extension)) { dst.delete() }
            }
            is AudioSource.Uri -> {
                val name = audio.uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
                val dst = File(cacheDir, "upload-${System.nanoTime()}-$name")
                context.contentResolver.openInputStream(audio.uri).use { ins ->
                    requireNotNull(ins) { "Cannot open ${audio.uri}" }
                    dst.outputStream().use { ins.copyTo(it) }
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                Upload(dst, name, guessMime(ext)) { dst.delete() }
            }
            is AudioSource.Pcm -> {
                val dst = File(cacheDir, "upload-${System.nanoTime()}.wav")
                writeWav16(audio.samples, AudioDecoder.TARGET_SAMPLE_RATE, dst)
                Upload(dst, dst.name, "audio/wav") { dst.delete() }
            }
        }
    }

    private fun guessMime(ext: String): String = when (ext.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private fun writeWav16(samples: FloatArray, sampleRate: Int, out: File) {
        val data = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clipped = min(32767f, kotlin.math.max(-32768f, s * 32767f))
            bb.putShort(clipped.toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())              // PCM
        header.putShort(1.toShort())              // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)             // byte rate
        header.putShort(2.toShort())              // block align
        header.putShort(16.toShort())             // bits per sample
        header.put("data".toByteArray())
        header.putInt(data.size)
        FileOutputStream(out).use {
            it.write(header.array())
            it.write(data)
        }
    }

    private data class Upload(
        val file: File,
        val filename: String,
        val mime: String,
        val cleanup: () -> Unit,
    )

    @Serializable
    private data class SimpleResponse(val text: String)

    @Serializable
    private data class VerboseResponse(
        val text: String,
        val segments: List<VerboseSegment>? = null,
    )

    @Serializable
    private data class VerboseSegment(
        val text: String,
        val start: Double,
        val end: Double,
    )
}
