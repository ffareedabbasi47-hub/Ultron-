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

    /** Linear resampling (speech quality is plenty for 16-bit mono TTS audio). */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val outLen = (pcm.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val f = (pos - i0).toFloat()
            out[i] = (pcm[i0] + (pcm[i1] - pcm[i0]) * f).toInt().toShort()
        }
        return out
    }

    class Decoded(val pcm: ShortArray, val sampleRate: Int)

    /** Parses a RIFF/WAVE file (what TextToSpeech.synthesizeToFile writes): 16-bit PCM, mono or stereo (mixed down). */
    fun parse(b: ByteArray): Decoded? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        fun i16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        var p = 12; var rate = 0; var ch = 1; var bits = 16
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = i32(p + 4)
            if (id == "fmt " && p + 8 + 16 <= b.size) { ch = i16(p + 10); rate = i32(p + 12); bits = i16(p + 22) }
            if (id == "data") {
                if (bits != 16 || rate <= 0 || ch !in 1..2) return null
                val start = p + 8
                // Streaming writers leave the size as 0 or 0xFFFFFFFF: then the data runs to the end of the file.
                val end = if (len <= 0 || start.toLong() + len > b.size) b.size else start + len
                val frames = (end - start) / (2 * ch)
                val out = ShortArray(frames) { i ->
                    val o = start + i * 2 * ch
                    val l = (b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)
                    if (ch == 1) l.toShort() else {
                        val r = (b[o + 2].toInt() and 0xFF) or (b[o + 3].toInt() shl 8)
                        ((l + r) / 2).toShort()
                    }
                }
                return Decoded(out, rate)
            }
            p += 8 + len + (len and 1)
            if (len < 0) return null
        }
        return null
    }
}
