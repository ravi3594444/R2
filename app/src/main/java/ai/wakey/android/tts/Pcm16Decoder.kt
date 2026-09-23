package ai.wakey.android.tts

/**
 * Turns a little-endian 16-bit PCM byte stream into samples. Network reads can end mid-sample, so an odd trailing
 * byte is carried into the next [decode] call. One instance per stream.
 */
internal class Pcm16Decoder {
    private var carry = NO_CARRY

    /** Decodes the first [length] bytes of [src] into [out], which must hold `(length + 1) / 2` samples; returns the sample count. */
    fun decode(src: ByteArray, length: Int, out: ShortArray): Int {
        var count = 0
        var i = 0
        if (carry != NO_CARRY && length > 0) {
            out[count++] = sample(carry, src[0].toInt())
            carry = NO_CARRY
            i = 1
        }
        while (i + 1 < length) {
            out[count++] = sample(src[i].toInt(), src[i + 1].toInt())
            i += 2
        }
        if (i < length) carry = src[i].toInt() and 0xFF
        return count
    }

    private fun sample(low: Int, high: Int): Short = ((high shl 8) or (low and 0xFF)).toShort()

    private companion object {
        const val NO_CARRY = -1
    }
}
