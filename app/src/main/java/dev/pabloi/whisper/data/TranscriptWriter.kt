package dev.pabloi.whisper.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/**
 * Writes a transcript file alongside the source audio as segments arrive.
 *
 * "Same folder as the source" only works for SAF URIs under `primary:Download/…`
 * because MediaStore's `Downloads` collection accepts `RELATIVE_PATH` values
 * rooted at `Download/`. URIs outside that tree (Music/, MediaStore audio
 * media, etc.) fall back to `Download/Whispr/` — Android doesn't let an app
 * write to arbitrary public folders without an explicit
 * `ACTION_OPEN_DOCUMENT_TREE` grant we don't currently request.
 *
 * Each `appendSegment` flushes immediately so the file is visible / readable
 * even if the app dies mid-run.
 */
class TranscriptWriter(
    private val context: Context,
    private val sourceUri: Uri,
) : AutoCloseable {

    private var output: OutputStream? = null
    private var destinationUri: Uri? = null
    var displayPath: String = ""
        private set

    /** Returns the destination URI on success, null on failure. */
    fun open(): Uri? {
        val sourceName = querySourceDisplayName(sourceUri)
        val baseName = sourceName.substringBeforeLast('.', sourceName).ifBlank { "transcript" }
        val fileName = "${baseName}_transcript.txt"
        val sameFolder = sourceParentRelPath(sourceUri)
        val relativePath = sameFolder ?: FALLBACK_RELATIVE_PATH

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        return try {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            val stream = context.contentResolver.openOutputStream(uri, "w") ?: run {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            output = stream
            destinationUri = uri
            displayPath = "$relativePath/$fileName"
            stream.write("# Whispr transcript\n".toByteArray())
            stream.write("# Source: $sourceName\n\n".toByteArray())
            stream.flush()
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open transcript file: ${t.message}", t)
            null
        }
    }

    fun appendSegment(startSec: Double?, endSec: Double?, text: String) {
        val stream = output ?: return
        if (text.isEmpty()) return
        val stamp = if (startSec != null && endSec != null) "[${fmt(startSec)}–${fmt(endSec)}] " else ""
        try {
            stream.write("$stamp$text\n".toByteArray())
            stream.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to append segment: ${t.message}")
        }
    }

    fun appendFooter(footer: String) {
        val stream = output ?: return
        try {
            stream.write("\n$footer\n".toByteArray())
            stream.flush()
        } catch (_: Throwable) { /* ignore */ }
    }

    override fun close() {
        runCatching { output?.close() }
        output = null
    }

    private fun querySourceDisplayName(uri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    /** Best-effort: pull the parent folder relative to primary external storage. */
    private fun sourceParentRelPath(uri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) return@runCatching null
            val volume = docId.substring(0, colon)
            val path = docId.substring(colon + 1)
            val parent = path.substringBeforeLast('/', "")
            // MediaStore.Downloads only accepts RELATIVE_PATH values rooted at "Download/"
            if (volume == "primary" && (parent == "Download" || parent.startsWith("Download/"))) {
                if (parent.endsWith("/")) parent else "$parent/"
            } else null
        }.getOrNull()
    }

    private fun fmt(s: Double): String {
        val total = s.toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        private const val TAG = "TranscriptWriter"
        private const val FALLBACK_RELATIVE_PATH = "Download/Whispr/"
    }
}
