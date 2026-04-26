package dev.pabloi.whisper.engine.local

import ai.onnxruntime.OnnxJavaType
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

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

    private val spec = repo.spec
    override val id: String = "local-qnn-${spec.id}"
    override val displayName: String = "${spec.displayName} (on-device NPU)"

    private val numLayers = spec.numDecoderLayers
    private val numHeads = spec.numHeads
    private val dModel = spec.dModel
    private val headDim = spec.headDim
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
    private val warmUpMutex = Mutex()

    override suspend fun warmUp() = warmUpMutex.withLock {
        if (encoder != null) return@withLock
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
        // "burst" forces adsprpc into busy-poll mode with a 10 ms timeout —
        // fine for sub-100ms inference, but our encoder takes ~480 ms per
        // chunk and triggers a kernel-driver `plist_add` Oops in adsprpc on
        // sustained loads (see CLAUDE.md). "sustained_high_performance" pins
        // DVFS at a high steady state without the busy-poll path.
        qnnOpts["htp_performance_mode"] = "sustained_high_performance"
        qnnOpts["qnn_context_priority"] = "high"
        opts.addQnn(qnnOpts)
        return opts
    }

    override fun transcribe(audio: AudioSource, options: TranscribeOptions): Flow<TranscribeEvent> = flow {
        val t0 = System.currentTimeMillis()
        warmUp()
        emit(TranscribeEvent.Progress(0.01f, "Decoding audio"))

        val chunkLen = MelSpectrogram.N_SAMPLES
        val mel = MelSpectrogram()
        val sbOut = StringBuilder()
        var cIdx = 0
        var failed = false

        // Stream chunks straight off MediaCodec — never materialize the full
        // PCM. Memory stays bounded regardless of input length.
        val source: Flow<FloatArray> = when (audio) {
            is AudioSource.Pcm -> chunkPrebufferedPcm(audio.samples, chunkLen)
            is AudioSource.File -> AudioDecoder.streamMonoF32(audio.path, chunkLen)
            is AudioSource.Uri -> AudioDecoder.streamMonoF32(context, audio.uri, chunkLen)
        }

        try {
            source.collect { chunk ->
                if (failed) return@collect

                // Cooperative cancellation: if the consumer cancelled the
                // outer flow we want to stop pulling more chunks.
                currentCoroutineContext().ensureActive()

                emit(TranscribeEvent.Progress(0.05f, "Mel ${cIdx + 1}"))

                val melT0 = System.nanoTime()
                val melFeatures = mel.compute(chunk)
                val melMs = (System.nanoTime() - melT0) / 1_000_000
                Log.i(TAG, "BENCH chunk=$cIdx mel=${melMs}ms samples=${chunk.size}")
                emit(TranscribeEvent.Progress(0.05f, "NPU transcribe ${cIdx + 1}"))

                val tokens = runChunkWithRecovery(cIdx, melFeatures, options)
                if (tokens == null) {
                    emit(TranscribeEvent.Failure(IllegalStateException(
                        "Engine failed on chunk ${cIdx + 1}; aborting after retry."
                    )))
                    failed = true
                    return@collect
                }

                val chunkStart = cIdx * MelSpectrogram.CHUNK_SECONDS.toDouble()
                val chunkEnd = chunkStart + MelSpectrogram.CHUNK_SECONDS
                val rawText = tokenizer!!.decode(tokens, skipSpecial = true).trim()
                // Whisper emits per-utterance timestamp tokens (`<|X.XX|>`,
                // chunk-local 0–30 s); shift them to global file time by
                // adding the chunk's start offset.
                val text = shiftWhisperTimestamps(rawText, chunkStart)
                if (text.isNotEmpty()) {
                    emit(TranscribeEvent.Segment(text, chunkStart, chunkEnd))
                    if (sbOut.isNotEmpty()) sbOut.append(' ')
                    sbOut.append(text)
                }

                // Periodic preventative session recreate. The Hexagon NPU's
                // QNN context can accumulate residual state across many
                // back-to-back encoder/decoder runs in a single session;
                // force a fresh session every SESSION_RESET_EVERY chunks.
                if ((cIdx + 1) % SESSION_RESET_EVERY == 0) {
                    Log.i(TAG, "Periodic session recreate at chunk=${cIdx + 1}")
                    runCatching { recreateSessions() }
                        .onFailure { Log.w(TAG, "Periodic recreate failed: ${it.message}") }
                }
                cIdx++
                yield()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe stream failed: ${t.message}", t)
            emit(TranscribeEvent.Failure(t))
            return@flow
        }

        if (!failed) {
            emit(TranscribeEvent.Final(sbOut.toString(), System.currentTimeMillis() - t0))
        }
    }.flowOn(Dispatchers.Default)

    /** Slice an already-loaded PCM array into [chunkLen]-sized FloatArrays, padding the last. */
    private fun chunkPrebufferedPcm(pcm: FloatArray, chunkLen: Int): Flow<FloatArray> = flow {
        if (pcm.isEmpty()) return@flow
        var i = 0
        while (i < pcm.size) {
            val out = FloatArray(chunkLen)
            val n = minOf(chunkLen, pcm.size - i)
            System.arraycopy(pcm, i, out, 0, n)
            emit(out)
            i += chunkLen
        }
    }

    /** Run one chunk; if QNN throws, recreate sessions and retry once. Returns null on second failure. */
    private suspend fun runChunkWithRecovery(
        cIdx: Int,
        mel: FloatArray,
        options: TranscribeOptions,
    ): IntArray? {
        return try {
            transcribeChunk(mel, options)
        } catch (t: Throwable) {
            Log.w(TAG, "chunk=$cIdx failed (${t.javaClass.simpleName}: ${t.message}); recreating sessions and retrying")
            try {
                recreateSessions()
                transcribeChunk(mel, options)
            } catch (t2: Throwable) {
                Log.e(TAG, "chunk=$cIdx retry failed (${t2.javaClass.simpleName}: ${t2.message})")
                null
            }
        }
    }

    private suspend fun recreateSessions() = warmUpMutex.withLock {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        val e = OrtEnvironment.getEnvironment()
        encoder = e.createSession(repo.assets.encoderOnnx.absolutePath, buildQnnSessionOptions())
        decoder = e.createSession(repo.assets.decoderOnnx.absolutePath, buildQnnSessionOptions())
        env = e
    }

    private fun transcribeChunk(mel: FloatArray, options: TranscribeOptions): IntArray {
        val env = this.env!!
        val encoder = this.encoder!!
        val decoder = this.decoder!!
        val tok = this.tokenizer!!

        val melShape = longArrayOf(1, MelSpectrogram.N_MELS_V3.toLong(), MelSpectrogram.N_FRAMES.toLong())
        // QNN-precompiled bundles use fp16 throughout; convert mel f32 -> fp16 short bits.
        val melHalf = ShortArray(mel.size)
        for (i in mel.indices) melHalf[i] = HalfFloat.fromFloat(mel[i])
        val melTensor = OnnxTensor.createTensor(
            env, ShortBuffer.wrap(melHalf), melShape, OnnxJavaType.FLOAT16
        )

        // Hold encoder outputs alive for the whole decode loop; they feed
        // unchanged into the decoder's cross-attention cache inputs.
        val encT0 = System.nanoTime()
        val encResult = try {
            encoder.run(mapOf("input_features" to melTensor))
        } finally {
            melTensor.close()
        }
        val encMs = (System.nanoTime() - encT0) / 1_000_000
        Log.i(TAG, "BENCH encoder=${encMs}ms")

        try {
            val crossByName = HashMap<String, OnnxTensor>()
            for (name in encoder.outputNames) {
                crossByName[name] = encResult.get(name).get() as OnnxTensor
            }
            val decT0 = System.nanoTime()
            var decSteps = 0

            // Decoder state — reused across steps. All float-typed tensors here
            // are fp16 (the QNN bundle was compiled in fp16); we hold them as
            // direct ShortBuffers carrying the fp16 bit pattern.
            val maskNegHalf = HalfFloat.fromFloat(maskNeg)
            val zeroHalf: Short = 0
            val attentionMask = ShortArray(meanDecodeLen) { maskNegHalf }
            val kSelfBufs = Array(numLayers) { directShorts(kSelfElems) }
            val vSelfBufs = Array(numLayers) { directShorts(vSelfElems) }
            val inputIdsBuf = IntBuffer.wrap(IntArray(1))
            val positionBuf = IntBuffer.wrap(IntArray(1))
            val maskShape = longArrayOf(1, 1, 1, meanDecodeLen.toLong())

            val outIds = ArrayList<Int>(meanDecodeLen)
            outIds.add(tok.sotId)

            for (n in 0 until meanDecodeLen - 1) {
                attentionMask[meanDecodeLen - n - 1] = zeroHalf
                inputIdsBuf.put(0, outIds[n])
                positionBuf.put(0, n)

                val input = HashMap<String, OnnxTensor>()
                val owned = ArrayList<OnnxTensor>()
                try {
                    fun add(name: String, t: OnnxTensor, own: Boolean = true) {
                        input[name] = t
                        if (own) owned.add(t)
                    }
                    inputIdsBuf.rewind(); positionBuf.rewind()
                    add("input_ids", OnnxTensor.createTensor(env, inputIdsBuf, longArrayOf(1, 1)))
                    add("attention_mask", OnnxTensor.createTensor(
                        env, ShortBuffer.wrap(attentionMask), maskShape, OnnxJavaType.FLOAT16
                    ))
                    add("position_ids", OnnxTensor.createTensor(env, positionBuf, longArrayOf(1)))
                    for (i in 0 until numLayers) {
                        // ORT validates `remaining() == numElements`; rewind the
                        // duplicate so the prior step's `put` doesn't leave us
                        // with a fully-consumed buffer (remaining = 0).
                        add("k_cache_self_${i}_in", OnnxTensor.createTensor(
                            env, kSelfBufs[i].duplicate().also { it.rewind() }, kSelfShape, OnnxJavaType.FLOAT16
                        ))
                        add("v_cache_self_${i}_in", OnnxTensor.createTensor(
                            env, vSelfBufs[i].duplicate().also { it.rewind() }, vSelfShape, OnnxJavaType.FLOAT16
                        ))
                        // Cross cache is owned by encResult; must not be closed here.
                        add("k_cache_cross_$i", crossByName["k_cache_cross_$i"]!!, own = false)
                        add("v_cache_cross_$i", crossByName["v_cache_cross_$i"]!!, own = false)
                    }

                    val result = decoder.run(input)
                    decSteps++
                    try {
                        val logits = (result.get("logits").get() as OnnxTensor).shortBuffer
                        val nextId = argmaxFp16(logits)
                        if (nextId == tok.eosId) break
                        outIds.add(nextId)
                        for (i in 0 until numLayers) {
                            val kOut = (result.get("k_cache_self_${i}_out").get() as OnnxTensor).shortBuffer
                            val vOut = (result.get("v_cache_self_${i}_out").get() as OnnxTensor).shortBuffer
                            kOut.rewind(); kSelfBufs[i].rewind(); kSelfBufs[i].put(kOut)
                            vOut.rewind(); vSelfBufs[i].rewind(); vSelfBufs[i].put(vOut)
                        }
                    } finally { result.close() }
                } finally {
                    owned.forEach { runCatching { it.close() } }
                }
            }
            val decMs = (System.nanoTime() - decT0) / 1_000_000
            val perStep = if (decSteps > 0) decMs / decSteps else 0
            Log.i(TAG, "BENCH decoder=${decMs}ms steps=$decSteps perStep=${perStep}ms tokens=${outIds.size - 1}")
            return outIds.drop(1).toIntArray()
        } finally {
            encResult.close()
        }
    }

    private fun shiftWhisperTimestamps(text: String, offsetSec: Double): String {
        if (offsetSec == 0.0 || !text.contains("<|")) return text
        return WHISPER_TIMESTAMP_RX.replace(text) { m ->
            val shifted = m.groupValues[1].toDouble() + offsetSec
            "<|" + String.format(java.util.Locale.ROOT, "%.2f", shifted) + "|>"
        }
    }

    private fun argmaxFp16(logits: ShortBuffer): Int {
        logits.rewind()
        var best = 0
        var bestV = HalfFloat.toFloat(logits.get(0))
        val n = logits.limit()
        var i = 1
        while (i < n) {
            val v = HalfFloat.toFloat(logits.get(i))
            if (v > bestV) { bestV = v; best = i }
            i++
        }
        return best
    }

    /** Direct ShortBuffer of n zeroed shorts — native-endian for ORT. */
    private fun directShorts(n: Int): ShortBuffer =
        ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    override fun close() {
        encoder?.close(); encoder = null
        decoder?.close(); decoder = null
        env = null
    }

    companion object {
        private const val TAG = "LocalQnnWhisperEngine"
        /** Force a session close+reopen every N chunks to bound DSP context growth. */
        private const val SESSION_RESET_EVERY = 8
        /** Matches Whisper's per-utterance timestamp tokens `<|3.84|>`. */
        private val WHISPER_TIMESTAMP_RX = Regex("""<\|(\d+\.\d{1,3})\|>""")
    }
}
