package dev.pabloi.whisper.engine.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dev.pabloi.whisper.audio.AudioDecoder
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.TranscriptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Whisper-large-v3-turbo running on the Hexagon NPU via ONNX Runtime's
 * QNN execution provider, loading Qualcomm's precompiled QNN-ONNX assets.
 *
 * Tensor layout matches qualcomm/ai-hub-models hf_whisper/model.py:
 *
 *   encoder:
 *     in : input_features      [1, 128, 3000] f32
 *     out: k_cache_cross_{i}   [20, 1, 64, 1500] f32   for i in 0..3
 *          v_cache_cross_{i}   [20, 1, 1500, 64] f32
 *
 *   decoder (per step):
 *     in : input_ids           [1, 1]   i32
 *          attention_mask      [1, 1, 1, 200] f32
 *          k_cache_self_{i}_in [20, 1, 64, 199] f32
 *          v_cache_self_{i}_in [20, 1, 199, 64] f32
 *          k_cache_cross_{i}   [20, 1, 64, 1500] f32   (from encoder)
 *          v_cache_cross_{i}   [20, 1, 1500, 64] f32
 *          position_ids        [1] i32
 *     out: logits              [1, 51866, 1, 1] f32
 *          k_cache_self_{i}_out, v_cache_self_{i}_out  (shapes match *_in)
 *
 * Decode loop (greedy argmax, per ai-hub-models reference):
 *   output_ids = [SOT]; position = 0
 *   mask = all MASK_NEG; self-cache = zeros
 *   for n in 0..MEAN_DECODE_LEN-1:
 *     mask[MEAN_DECODE_LEN - n - 1] = 0        // unmask current slot
 *     run decoder with output_ids[n] as input_ids
 *     next = argmax(logits); position += 1
 *     if next == EOT: break
 *     output_ids += next
 *     self-cache <- decoder self-cache outputs
 */
class LocalQnnWhisperEngine(
    private val context: Context,
    private val repo: ModelRepository,
) : TranscriptionEngine {

    override val id: String = "local-qnn-whisper-large-v3-turbo"
    override val displayName: String = "Whisper Large v3 Turbo (on-device NPU)"

    private val numLayers = 4
    private val numHeads = 20
    private val dModel = 1280
    private val headDim = dModel / numHeads         // 64
    private val audioEmbLen = 1500
    private val meanDecodeLen = 200
    private val maskNeg = -100f

    private val kSelfShape = longArrayOf(numHeads.toLong(), 1, headDim.toLong(), (meanDecodeLen - 1).toLong())
    private val vSelfShape = longArrayOf(numHeads.toLong(), 1, (meanDecodeLen - 1).toLong(), headDim.toLong())
    private val kSelfElems = numHeads * headDim * (meanDecodeLen - 1)
    private val vSelfElems = numHeads * (meanDecodeLen - 1) * headDim

    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: WhisperTokenizer? = null

    @Synchronized
    override suspend fun warmUp() {
        if (encoder != null) return
        require(repo.isInstalled()) { "Whisper assets not installed; download first" }
        val e = OrtEnvironment.getEnvironment()
        encoder = e.createSession(repo.assets.encoderOnnx.absolutePath, buildQnnSessionOptions())
        decoder = e.createSession(repo.assets.decoderOnnx.absolutePath, buildQnnSessionOptions())
        tokenizer = WhisperTokenizer.fromTokenizerJson(repo.assets.tokenizerJson)
        env = e
        Log.i(TAG, "encoder inputs=${encoder!!.inputNames} outputs=${encoder!!.outputNames}")
        Log.i(TAG, "decoder inputs=${decoder!!.inputNames} outputs=${decoder!!.outputNames}")
    }

    private fun buildQnnSessionOptions(): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        val qnnOpts = HashMap<String, String>()
        // ORT looks for this .so in the app's native lib dir; the AAR
        // com.microsoft.onnxruntime:onnxruntime-android-qnn bundles it.
        qnnOpts["backend_path"] = "libQnnHtp.so"
        qnnOpts["htp_performance_mode"] = "burst"
        qnnOpts["qnn_context_priority"] = "high"
        opts.addQnn(qnnOpts)
        return opts
    }

    override fun transcribe(audio: AudioSource, options: TranscribeOptions): Flow<TranscribeEvent> = flow {
        val t0 = System.currentTimeMillis()
        warmUp()
        emit(TranscribeEvent.Progress(0.01f, "Decoding audio"))
        val pcm = when (audio) {
            is AudioSource.Pcm -> audio.samples
            is AudioSource.File -> AudioDecoder.decodeToMonoF32(audio.path)
            is AudioSource.Uri -> AudioDecoder.decodeToMonoF32(context, audio.uri)
        }

        val chunks = chunkAudio(pcm)
        val mel = MelSpectrogram()
        val sbOut = StringBuilder()
        for ((cIdx, chunk) in chunks.withIndex()) {
            emit(TranscribeEvent.Progress(
                0.05f + 0.9f * cIdx / chunks.size,
                "Mel ${cIdx + 1}/${chunks.size}"
            ))
            val melFeatures = mel.compute(chunk)
            emit(TranscribeEvent.Progress(
                0.05f + 0.9f * cIdx / chunks.size,
                "NPU transcribe ${cIdx + 1}/${chunks.size}"
            ))
            val tokens = transcribeChunk(melFeatures, options)
            val chunkStart = cIdx * MelSpectrogram.CHUNK_SECONDS.toDouble()
            val chunkEnd = chunkStart + MelSpectrogram.CHUNK_SECONDS
            val text = tokenizer!!.decode(tokens, skipSpecial = true).trim()
            if (text.isNotEmpty()) {
                emit(TranscribeEvent.Segment(text, chunkStart, chunkEnd))
                if (sbOut.isNotEmpty()) sbOut.append(' ')
                sbOut.append(text)
            }
        }
        emit(TranscribeEvent.Final(sbOut.toString(), System.currentTimeMillis() - t0))
    }.flowOn(Dispatchers.Default)

    private fun chunkAudio(pcm: FloatArray): List<FloatArray> {
        val chunkLen = MelSpectrogram.N_SAMPLES
        if (pcm.size <= chunkLen) return listOf(pcm)
        val result = ArrayList<FloatArray>()
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + chunkLen, pcm.size)
            val slice = FloatArray(chunkLen)
            System.arraycopy(pcm, i, slice, 0, end - i)
            result.add(slice)
            i += chunkLen
        }
        return result
    }

    private fun transcribeChunk(mel: FloatArray, options: TranscribeOptions): IntArray {
        val env = this.env!!
        val encoder = this.encoder!!
        val decoder = this.decoder!!
        val tok = this.tokenizer!!

        val melShape = longArrayOf(1, MelSpectrogram.N_MELS_V3.toLong(), MelSpectrogram.N_FRAMES.toLong())
        val melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), melShape)

        // Hold encoder outputs alive for the whole decode loop; they feed
        // unchanged into the decoder's cross-attention cache inputs.
        val encResult = try {
            encoder.run(mapOf("input_features" to melTensor))
        } finally {
            melTensor.close()
        }

        try {
            val crossByName = HashMap<String, OnnxTensor>()
            for (name in encoder.outputNames) {
                crossByName[name] = encResult.get(name).get() as OnnxTensor
            }

            // Decoder state — reused across steps.
            val attentionMask = FloatArray(meanDecodeLen) { maskNeg }
            val kSelfBufs = Array(numLayers) { directFloats(kSelfElems) }
            val vSelfBufs = Array(numLayers) { directFloats(vSelfElems) }
            val inputIdsBuf = IntBuffer.wrap(IntArray(1))
            val positionBuf = IntBuffer.wrap(IntArray(1))

            val outIds = ArrayList<Int>(meanDecodeLen)
            outIds.add(tok.sotId)

            for (n in 0 until meanDecodeLen - 1) {
                attentionMask[meanDecodeLen - n - 1] = 0f
                inputIdsBuf.put(0, outIds[n])
                positionBuf.put(0, n)

                val input = HashMap<String, OnnxTensor>()
                val owned = ArrayList<OnnxTensor>()
                try {
                    fun add(name: String, t: OnnxTensor, own: Boolean = true) {
                        input[name] = t
                        if (own) owned.add(t)
                    }
                    add("input_ids", OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, 1)))
                    add("attention_mask", OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(attentionMask),
                        longArrayOf(1, 1, 1, meanDecodeLen.toLong())
                    ))
                    add("position_ids", OnnxTensor.createTensor(env, positionBuf, longArrayOf(1)))
                    for (i in 0 until numLayers) {
                        add("k_cache_self_${i}_in",
                            OnnxTensor.createTensor(env, kSelfBufs[i].duplicate(), kSelfShape))
                        add("v_cache_self_${i}_in",
                            OnnxTensor.createTensor(env, vSelfBufs[i].duplicate(), vSelfShape))
                        // Cross cache is owned by encResult; must not be closed here.
                        add("k_cache_cross_$i", crossByName["k_cache_cross_$i"]!!, own = false)
                        add("v_cache_cross_$i", crossByName["v_cache_cross_$i"]!!, own = false)
                    }

                    val result = decoder.run(input)
                    try {
                        val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                        val nextId = argmax(logits)
                        if (nextId == tok.eosId) break
                        outIds.add(nextId)
                        for (i in 0 until numLayers) {
                            val kOut = (result.get("k_cache_self_${i}_out").get() as OnnxTensor).floatBuffer
                            val vOut = (result.get("v_cache_self_${i}_out").get() as OnnxTensor).floatBuffer
                            kOut.rewind(); kSelfBufs[i].rewind(); kSelfBufs[i].put(kOut)
                            vOut.rewind(); vSelfBufs[i].rewind(); vSelfBufs[i].put(vOut)
                        }
                    } finally { result.close() }
                } finally {
                    owned.forEach { runCatching { it.close() } }
                }
            }
            return outIds.drop(1).toIntArray()
        } finally {
            encResult.close()
        }
    }

    private fun argmax(logits: FloatBuffer): Int {
        logits.rewind()
        var best = 0
        var bestV = logits.get(0)
        val n = logits.limit()
        var i = 1
        while (i < n) {
            val v = logits.get(i)
            if (v > bestV) { bestV = v; best = i }
            i++
        }
        return best
    }

    /** Direct FloatBuffer of n zeroed floats — native-endian for ORT. */
    private fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun close() {
        encoder?.close(); encoder = null
        decoder?.close(); decoder = null
        env = null
    }

    companion object {
        private const val TAG = "LocalQnnWhisperEngine"
    }
}
