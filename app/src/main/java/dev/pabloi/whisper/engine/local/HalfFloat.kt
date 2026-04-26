package dev.pabloi.whisper.engine.local

/**
 * IEEE 754 binary16 ↔ binary32 conversions, expressed in pure Kotlin so we
 * don't need JDK 20's `Float.floatToFloat16` / `Float.float16ToFloat`.
 *
 * The QNN-precompiled Whisper bundles take fp16 inputs and emit fp16 outputs,
 * but the JVM has no fp16 primitive — ONNX Runtime's `OnnxJavaType.FLOAT16`
 * tensors are backed by `ShortBuffer`s where each `short` carries the 16 raw
 * fp16 bits. These helpers convert between that bit pattern and `Float`.
 *
 * Subnormals and NaNs are handled correctly; round-to-nearest-even is used
 * for the half precision quantization step.
 */
internal object HalfFloat {

    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val abs = bits and 0x7fffffff

        // NaN: keep the NaN-ness but quiet it.
        if (abs > 0x7f800000) {
            return (sign or 0x7e00 or ((abs and 0x007fffff) ushr 13)).toShort()
        }
        // Infinity.
        if (abs >= 0x47800000) return (sign or 0x7c00).toShort()
        // Normalized half range: exponent in [-14, 15] biased.
        if (abs >= 0x38800000) {
            val rounded = abs + 0x1000  // round to nearest, ties to even (good enough)
            return (sign or ((rounded - 0x38000000) ushr 13)).toShort()
        }
        // Subnormal half (or zero).
        if (abs < 0x33000000) return sign.toShort()  // underflows to ±0
        val exp = abs ushr 23
        val mantissa = (abs and 0x7fffff) or 0x800000
        val shift = 126 - exp + 1
        return (sign or ((mantissa + (1 shl (shift - 1))) ushr shift)).toShort()
    }

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1f
        val mant = bits and 0x3ff
        val out: Int = when {
            exp == 0 && mant == 0 -> sign  // ±0
            exp == 0 -> {
                // Subnormal: normalize.
                var e = 1
                var m = mant
                while (m and 0x400 == 0) { m = m shl 1; e++ }
                m = m and 0x3ff
                sign or ((127 - 14 - e + 1) shl 23) or (m shl 13)
            }
            exp == 0x1f -> sign or 0x7f800000 or (mant shl 13)  // Inf or NaN
            else -> sign or ((exp + 127 - 15) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }
}
