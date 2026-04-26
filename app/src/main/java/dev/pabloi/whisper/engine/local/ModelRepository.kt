package dev.pabloi.whisper.engine.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * On-demand fetcher for one Whisper variant's precompiled QNN-ONNX assets.
 *
 * One [ModelRepository] instance per [ModelSpec]; each lives in its own
 * `filesDir/models/<spec.id>/` subdirectory so multiple models can coexist.
 *
 * Each zip contains four files (encoder/decoder .onnx + matching `_qairt_context.bin`).
 * The .onnx wraps a QNN EP context node referencing the .bin by relative
 * name, so both must sit next to each other on disk. Tokenizer is fetched
 * separately from the corresponding `openai/whisper-*` HF repo.
 *
 * Resume is via HTTP `Range:` against a `bundle.zip.partial` temp file; the
 * extract step runs only once the full zip is on disk.
 */
class ModelRepository(context: Context, val spec: ModelSpec) {

    data class Assets(
        val encoderOnnx: File,
        val decoderOnnx: File,
        val tokenizerJson: File,
    ) {
        val ready: Boolean get() = encoderOnnx.exists() && decoderOnnx.exists() && tokenizerJson.exists()
    }

    private val modelRoot = File(context.filesDir, "models/${spec.id}").apply { mkdirs() }
    private val markerFile = File(modelRoot, ".complete")
    private val tempZip = File(modelRoot, "bundle.zip.partial")
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(60, TimeUnit.MINUTES)
        .build()

    val assets: Assets
        get() = Assets(
            encoderOnnx = File(modelRoot, "encoder.onnx"),
            decoderOnnx = File(modelRoot, "decoder.onnx"),
            tokenizerJson = File(modelRoot, "tokenizer.json"),
        )

    fun isInstalled(): Boolean = markerFile.exists() && assets.ready

    fun download(): Flow<Progress> = flow {
        if (isInstalled()) {
            emit(Progress(1f, "Already installed"))
            return@flow
        }
        markerFile.delete()
        emit(Progress(0f, "Contacting asset host"))

        val existing = if (tempZip.exists()) tempZip.length() else 0L
        val builder = Request.Builder().url(spec.zipUrl)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) error("HTTP ${resp.code} from ${spec.zipUrl}")
            val body = resp.body ?: error("Empty response")
            val contentLength = body.contentLength()
            val total = if (resp.code == 206) existing + contentLength else contentLength
            RandomAccessFile(tempZip, "rw").use { raf ->
                if (resp.code == 206) raf.seek(existing) else raf.setLength(0L)
                val buf = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    var written = if (resp.code == 206) existing else 0L
                    var lastTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        val now = System.nanoTime()
                        if (now - lastTick > 100_000_000L) {
                            emit(Progress(
                                fraction = if (total > 0) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
                                message = "Downloading " + humanBytes(written) + " / " + (if (total > 0) humanBytes(total) else "?")
                            ))
                            lastTick = now
                        }
                    }
                }
            }
        }
        emit(Progress(1f, "Download complete, extracting"))
        extract()
        tempZip.delete()
        if (!assets.encoderOnnx.exists() || !assets.decoderOnnx.exists()) {
            error("Archive did not contain expected encoder/decoder files")
        }

        emit(Progress(1f, "Fetching tokenizer"))
        downloadTokenizer()

        if (!assets.ready) error("Tokenizer download failed")
        markerFile.writeText("ok")
        emit(Progress(1f, "Installed"))
    }.flowOn(Dispatchers.IO)

    private fun downloadTokenizer() {
        val req = Request.Builder().url(spec.tokenizerUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from ${spec.tokenizerUrl}")
            val body = resp.body ?: error("Empty tokenizer response")
            FileOutputStream(assets.tokenizerJson).use { fos ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(1 shl 15)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun extract() {
        ZipFile(tempZip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (name !in KEEP) continue
                val out = File(modelRoot, name)
                FileOutputStream(out).use { fos ->
                    zf.getInputStream(e).use { ins ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                Log.i(TAG, "Extracted ${out.name} (${out.length()} bytes) for ${spec.id}")
            }
        }
    }

    fun clean() {
        modelRoot.listFiles()?.forEach { it.delete() }
    }

    private fun humanBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    data class Progress(val fraction: Float, val message: String)

    companion object {
        private const val TAG = "ModelRepository"
        private val KEEP = setOf(
            "encoder.onnx",
            "encoder_qairt_context.bin",
            "decoder.onnx",
            "decoder_qairt_context.bin",
        )
    }
}
