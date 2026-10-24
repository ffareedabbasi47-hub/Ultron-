package com.ultron.ai.domain.live

/** Wraps 16-bit mono PCM in a 44-byte RIFF/WAVE header (what Gemini accepts as audio/wav). */
object Wav {
    fun wrap(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".forEachIndexed { k, c -> out[k] = c.code.toByte() }
        i32(4, 36 + dataLen)
        "WAVEfmt ".forEachIndexed { k, c -> out[8 + k] = c.code.toByte() }
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, sampleRate); i32(28, sampleRate * 2); i16(32, 2); i16(34, 16)
        "data".forEachIndexed { k, c -> out[36 + k] = c.code.toByte() }
        i32(40, dataLen)
        var o = 44
        for (s in pcm) { out[o++] = s.toInt().toByte(); out[o++] = (s.toInt() shr 8).toByte() }
        return out
    }

    /** Little-endian 16-bit PCM bytes -> samples (odd trailing byte ignored). */
    fun pcmFromBytes(b: ByteArray): ShortArray = ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
}
