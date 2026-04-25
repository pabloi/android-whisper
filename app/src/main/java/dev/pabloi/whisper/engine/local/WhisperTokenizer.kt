package dev.pabloi.whisper.engine.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Decode-only Whisper tokenizer. Parses HuggingFace's `tokenizer.json`
 * (the unified v2 format) and provides an id -> text mapping with
 * GPT-2 byte-level decoding.
 *
 * We only need decoding: Whisper STT *emits* token ids, we turn them
 * into strings. If we later want to force a language or task prefix at
 * decode-time, we do it by id, not by re-tokenizing a string.
 */
class WhisperTokenizer private constructor(
    private val idToToken: Array<String?>,
    private val specialIds: Set<Int>,
    private val languageIds: Map<String, Int>,
    val sotId: Int,
    val eosId: Int,
    val transcribeId: Int,
    val translateId: Int,
    val noTimestampsId: Int,
    val timestampBeginId: Int,
) {
    /** Decode a list of token ids to a string; special tokens are dropped by default. */
    fun decode(ids: IntArray, skipSpecial: Boolean = true): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (skipSpecial && id in specialIds) continue
            val tok = idToToken.getOrNull(id) ?: continue
            sb.append(tok)
        }
        // Byte-level decode: each Unicode code point in `sb` maps back to a
        // single byte; the resulting byte sequence is a UTF-8 string.
        val bytes = ByteArray(sb.length)
        var n = 0
        var i = 0
        while (i < sb.length) {
            val cp = sb.codePointAt(i)
            i += Character.charCount(cp)
            val b = BYTE_DECODER[cp] ?: continue
            bytes[n++] = b.toByte()
        }
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    /** Token id for a given BCP-47-ish language hint like "en". Null if unknown. */
    fun languageTokenId(lang: String): Int? = languageIds[lang.lowercase()]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromTokenizerJson(file: File): WhisperTokenizer {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            return fromRoot(root)
        }

        private fun fromRoot(root: JsonObject): WhisperTokenizer {
            val modelObj = root["model"]!!.jsonObject
            val vocab = modelObj["vocab"]!!.jsonObject
            val addedTokens = root["added_tokens"]?.jsonArray

            val maxVocabId = vocab.values.maxOf { it.jsonPrimitive.int }
            val addedMax = addedTokens?.maxOfOrNull { it.jsonObject["id"]!!.jsonPrimitive.int } ?: -1
            val size = maxOf(maxVocabId, addedMax) + 1
            val arr = arrayOfNulls<String>(size)
            for ((tok, idElem) in vocab) arr[idElem.jsonPrimitive.int] = tok

            val specials = HashSet<Int>()
            val languageIds = HashMap<String, Int>()
            var sot = -1
            var eos = -1
            var transcribe = -1
            var translate = -1
            var noTs = -1
            var tsBegin = Int.MAX_VALUE
            addedTokens?.forEach { el ->
                val o = el.jsonObject
                val id = o["id"]!!.jsonPrimitive.int
                val content = o["content"]!!.jsonPrimitive.content
                val isSpecial = o["special"]?.jsonPrimitive?.booleanOrNull ?: false
                if (id < arr.size) arr[id] = content
                if (isSpecial) specials.add(id)
                when {
                    content == "<|startoftranscript|>" -> sot = id
                    content == "<|endoftext|>" -> eos = id
                    content == "<|transcribe|>" -> transcribe = id
                    content == "<|translate|>" -> translate = id
                    content == "<|notimestamps|>" -> noTs = id
                    content.startsWith("<|") && content.endsWith("|>") -> {
                        val inner = content.substring(2, content.length - 2)
                        if (inner.length == 2 && inner.all { it.isLetter() }) {
                            languageIds[inner.lowercase()] = id
                        }
                        if (inner.firstOrNull()?.isDigit() == true && inner.contains('.')) {
                            if (id < tsBegin) tsBegin = id
                        }
                    }
                }
            }
            if (sot < 0 || eos < 0) error("tokenizer.json missing Whisper specials")

            return WhisperTokenizer(
                idToToken = arr,
                specialIds = specials,
                languageIds = languageIds,
                sotId = sot,
                eosId = eos,
                transcribeId = transcribe.coerceAtLeast(0),
                translateId = translate.coerceAtLeast(0),
                noTimestampsId = noTs.coerceAtLeast(0),
                timestampBeginId = if (tsBegin == Int.MAX_VALUE) eos + 1 else tsBegin,
            )
        }

        // GPT-2 byte-level decoder: inverse of HuggingFace's bytes_to_unicode().
        // Each byte 0..255 maps to a unique printable Unicode code point; this
        // table is the standard mapping used by Whisper / GPT-2 / BART / etc.
        private val BYTE_DECODER: Map<Int, Int> = buildMap {
            val bs = ArrayList<Int>()
            for (b in '!'.code..'~'.code) bs.add(b)
            for (b in 0xA1..0xAC) bs.add(b)
            for (b in 0xAE..0xFF) bs.add(b)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            for (i in bs.indices) put(cs[i], bs[i])
        }
    }
}
