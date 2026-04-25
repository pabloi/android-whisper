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
 * On-demand fetcher for the precompiled QNN-ONNX Whisper assets.
 *
 * Asset manifest is Qualcomm's `release_assets.json` at
 *   https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo/raw/main/release_assets.json
 * which we consume here. For v1 we hard-target the chipset we care about —
 * the S24 Ultra's Snapdragon 8 Gen 3 for Galaxy (a.k.a. "qualcomm-snapdragon-8gen3"
 * in the manifest).
 *
 * The zip contains four files:
 *   encoder.onnx, encoder_qairt_context.bin
 *   decoder.onnx, decoder_qairt_context.bin
 * The .onnx wraps a QNN EP context node that references the .bin via its
 * relative name, so both must sit next to each other on disk.
 *
 * We download to a temp file first (so we can request HTTP Range resume if
 * a download is interrupted), then extract. The extract step is cheap —
 * under a minute on device — and the temp file is deleted once we're done.
 */
class ModelRepository(context: Context) {

    data class Assets(
        val encoderOnnx: File,
        val decoderOnnx: File,
        val tokenizerJson: File,
    ) {
        val ready: Boolean get() = encoderOnnx.exists() && decoderOnnx.exists() && tokenizerJson.exists()
    }

    private val modelRoot = File(context.filesDir, "models/whisper_large_v3_turbo_qnn").apply { mkdirs() }
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
        // Leave stale .bin/.onnx behind if partial; download only overwrites
        // the .zip.partial, and extract runs last.
        markerFile.delete()
        emit(Progress(0f, "Contacting asset host"))

        val existing = if (tempZip.exists()) tempZip.length() else 0L
        val builder = Request.Builder().url(ZIP_URL)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) error("HTTP ${resp.code} from $ZIP_URL")
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
                        // throttle emits to ~10 Hz so the Flow collector isn't flooded
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
        val req = Request.Builder().url(TOKENIZER_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} from $TOKENIZER_URL")
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
                Log.i(TAG, "Extracted ${out.name} (${out.length()} bytes)")
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
        // Resolved from release_assets.json on 2026-04. If Qualcomm bumps the
        // version, re-fetch the manifest and update this URL.
        const val ZIP_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/" +
            "qai-hub-models/models/whisper_large_v3_turbo/releases/v0.51.0/" +
            "whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip"
        const val TOKENIZER_URL =
            "https://huggingface.co/openai/whisper-large-v3-turbo/resolve/main/tokenizer.json"
        private val KEEP = setOf(
            "encoder.onnx",
            "encoder_qairt_context.bin",
            "decoder.onnx",
            "decoder_qairt_context.bin",
        )
    }
}
