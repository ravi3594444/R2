package ai.wakey.android.wake

import android.content.Context

/** A wake phrase converted into the model's BPE tokens, ready for sherpa-onnx. */
data class EncodedKeyword(
    /** Upper-cased, normalised phrase, e.g. "HEY WAKEY". */
    val phrase: String,
    /** e.g. ["▁HE", "Y", "▁WA", "KE", "Y"]. */
    val tokens: List<String>,
) {
    /** Keyword tag reported back by sherpa-onnx on detection. */
    val tag: String get() = phrase.replace(' ', '_')

    /** One sherpa-onnx keywords line: `▁HE Y ▁WA KE Y :boost #threshold @HEY_WAKEY`. */
    fun toSherpaLine(boostScore: Float, threshold: Float): String =
        tokens.joinToString(" ") + " :%.2f #%.2f @%s".format(java.util.Locale.US, boostScore, threshold, tag)
}

class KeywordEncodingException(message: String) : IllegalArgumentException(message)

/**
 * Converts an English wake phrase into the KWS model's SentencePiece BPE tokens, on device.
 * STUB: implemented by the wake-word module.
 */
class KeywordEncoder(bpeModel: ByteArray, validTokens: Set<String>) {
    fun encode(phrase: String): EncodedKeyword = throw KeywordEncodingException("Not implemented")

    companion object {
        const val ASSET_DIR = "kws"
        fun fromAssets(context: Context): KeywordEncoder = throw UnsupportedOperationException("Not implemented")
    }
}
