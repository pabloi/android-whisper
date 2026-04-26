package dev.pabloi.whisper.engine.local

/**
 * One row per Whisper variant precompiled by Qualcomm AI Hub for the
 * snapdragon-8gen3 chipset. The shape constants below drive the encoder/
 * decoder I/O contract in [LocalQnnWhisperEngine] — they are taken from
 * OpenAI's reference Whisper configs and must match the precompiled
 * QNN-ONNX bundle. If Qualcomm bumps the version, refresh `zipUrl` from
 * the model's `release_assets.json` on Hugging Face.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val approxSizeMb: Int,
    val zipUrl: String,
    val tokenizerUrl: String,
    val numDecoderLayers: Int,
    val numHeads: Int,
    val dModel: Int,
) {
    val headDim: Int get() = dModel / numHeads
}

object ModelCatalog {
    private const val S3 = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
    private const val HF = "https://huggingface.co/openai"
    private const val V = "v0.51.0"
    private const val CHIP = "qualcomm_snapdragon_8gen3"

    private fun zip(model: String) =
        "$S3/$model/releases/$V/$model-precompiled_qnn_onnx-float-$CHIP.zip"

    private fun tok(model: String) = "$HF/$model/resolve/main/tokenizer.json"

    val specs: List<ModelSpec> = listOf(
        ModelSpec(
            id = "whisper_tiny",
            displayName = "Whisper Tiny (~80 MB)",
            approxSizeMb = 80,
            zipUrl = zip("whisper_tiny"),
            tokenizerUrl = tok("whisper-tiny"),
            numDecoderLayers = 4, numHeads = 6, dModel = 384,
        ),
        ModelSpec(
            id = "whisper_base",
            displayName = "Whisper Base (~150 MB)",
            approxSizeMb = 150,
            zipUrl = zip("whisper_base"),
            tokenizerUrl = tok("whisper-base"),
            numDecoderLayers = 6, numHeads = 8, dModel = 512,
        ),
        ModelSpec(
            id = "whisper_small",
            displayName = "Whisper Small (~500 MB)",
            approxSizeMb = 500,
            zipUrl = zip("whisper_small"),
            tokenizerUrl = tok("whisper-small"),
            numDecoderLayers = 12, numHeads = 12, dModel = 768,
        ),
        ModelSpec(
            id = "whisper_large_v3_turbo",
            displayName = "Whisper Large v3 Turbo (~1.6 GB)",
            approxSizeMb = 1600,
            zipUrl = zip("whisper_large_v3_turbo"),
            tokenizerUrl = tok("whisper-large-v3-turbo"),
            numDecoderLayers = 4, numHeads = 20, dModel = 1280,
        ),
    )

    val byId: Map<String, ModelSpec> = specs.associateBy { it.id }

    val default: ModelSpec = byId.getValue("whisper_large_v3_turbo")

    fun resolve(id: String): ModelSpec = byId[id] ?: default
}
