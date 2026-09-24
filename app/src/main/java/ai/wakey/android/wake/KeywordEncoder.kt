package ai.wakey.android.wake

import android.content.Context
import java.io.IOException
import java.util.Locale

/** A wake phrase converted into the model's BPE tokens, ready for sherpa-onnx. */
data class EncodedKeyword(
    /** Upper-cased, normalised phrase, e.g. "HEY WAKEY". */
    val phrase: String,
    /** e.g. ["▁HE", "Y", "▁WA", "KE", "Y"]. */
    val tokens: List<String>,
    /**
     * Tokens of other ways the phrase is commonly said or heard (see [PronunciationVariants]), e.g.
     * ["▁HE", "Y", "▁WA", "K", "Y"] for "HEY WAKY". Spotted in the same stream and reported as [phrase].
     */
    val variants: List<List<String>> = emptyList(),
    /**
     * Tokens of looser sound-alikes ("HEY WIKY", "HEY RICKY"), spotted in the same stream under
     * [checkTag]. They catch many wake phrases the model mishears, and some other speech, so a
     * detection of one is confirmed with speech recognition before Wakey responds.
     */
    val checkVariants: List<List<String>> = emptyList(),
) {
    /** Keyword tag reported back by sherpa-onnx on detection, for [tokens] and every variant. */
    val tag: String get() = phrase.replace(' ', '_')

    /** Tag reported for [checkVariants]. */
    val checkTag: String get() = tag + CHECK_SUFFIX

    /**
     * The keywords of one sherpa-onnx stream: a `tokens :boost #threshold @TAG` line for [tokens] and
     * each variant, joined by '/', e.g. `▁HE Y ▁WA KE Y :1.50 #0.18 @HEY_WAKEY/▁HE Y ▁WA K Y :1.50 ...`,
     * then a line per check variant scored with [check] and tagged [checkTag].
     */
    fun toSherpaKeywords(boostScore: Float, threshold: Float, check: KeywordScoring? = null): String {
        val scoring = " :%.2f #%.2f @%s".format(Locale.US, boostScore, threshold, tag)
        val sure = listOf(tokens) + variants
        val lines = sure.map { it.joinToString(" ") + scoring }.toMutableList()
        if (check != null) {
            val checkScoring = " :%.2f #%.2f @%s".format(Locale.US, check.boost, check.threshold, checkTag)
            checkVariants.filter { it !in sure }.mapTo(lines) { it.joinToString(" ") + checkScoring }
        }
        return lines.joinToString("/")
    }

    companion object {
        const val CHECK_SUFFIX = "__CHECK"
    }
}

class KeywordEncodingException(message: String) : IllegalArgumentException(message)

/**
 * Converts an English wake phrase into the KWS model's SentencePiece tokens, on device, so changing
 * the phrase in Settings changes what the detector listens for without retraining anything.
 *
 * The tokens are exactly what Python `sentencepiece` produces for `encode(phrase.upper())`
 * (checked against 48 reference phrases in the unit tests). Phrases the detector cannot spot
 * reliably are rejected with a message that can be shown as-is in Settings. Each phrase also gets
 * the tokens of its [PronunciationVariants]: the detector spots common ones under the same tag and
 * looser sound-alikes under a tag whose detections are confirmed first.
 *
 * @param bpeModel the model's `bpe.model` (a SentencePiece unigram model).
 * @param validTokens the symbols in the model's `tokens.txt`.
 */
class KeywordEncoder(bpeModel: ByteArray, private val validTokens: Set<String>) {
    private val model = SentencePieceModel.parse(bpeModel)

    /** @throws KeywordEncodingException if [phrase] can't be used as a wake phrase. */
    fun encode(phrase: String): EncodedKeyword {
        val words = phrase.map { if (it in APOSTROPHES) '\'' else it }.joinToString("")
            .split(WHITESPACE).filter { it.isNotEmpty() }
        val display = words.joinToString(" ")
        if (words.isEmpty()) fail("Enter a wake phrase.")
        display.firstOrNull { !it.isAsciiLetter() && it != '\'' && it != ' ' }?.let { bad ->
            if (bad.isDigit()) fail("Spell numbers out as words (found “$bad”).")
            fail("Use only English letters, spaces and apostrophes (remove “$bad”).")
        }
        if (words.size > MAX_WORDS) fail("Use at most $MAX_WORDS words.")
        if (display.length > MAX_LENGTH) fail("Use at most $MAX_LENGTH characters.")
        words.firstOrNull { word -> word.none { it.isAsciiLetter() } }?.let { fail("“$it” is not a word.") }

        val upper = words.map { it.uppercase(Locale.ROOT) }
        val tokens = segment(upper) ?: fail("“$display” can't be spelled with the wake-word model's vocabulary.")
        tokens.firstOrNull { it !in validTokens }?.let {
            fail("“$display” can't be spelled with the wake-word model's vocabulary (missing “$it”).")
        }
        // One or two tokens (e.g. "Hi", "Everything") match everyday speech far too easily.
        if (display.count { it.isAsciiLetter() } < MIN_LETTERS || tokens.size < MIN_TOKENS) {
            fail("“$display” is too short to detect reliably. Use a longer phrase, like “Hey Wakey”.")
        }
        val variants = spell(PronunciationVariants.of(upper)).filter { it != tokens }
        val checks = spell(PronunciationVariants.checksOf(upper)).filter { it != tokens && it !in variants }
        return EncodedKeyword(upper.joinToString(" "), tokens, variants, checks)
    }

    /** Tokens of each respelling the model's vocabulary can express, without duplicates. */
    private fun spell(respellings: List<List<String>>): List<List<String>> = respellings
        .mapNotNull { words -> segment(words)?.takeIf { pieces -> pieces.all { it in validTokens } } }
        .distinct()

    private fun segment(words: List<String>): List<String>? =
        model.segment((if (model.addDummyPrefix) WORD_START else "") + words.joinToString(WORD_START))

    companion object {
        const val ASSET_DIR = "kws"
        const val MAX_WORDS = 6
        const val MAX_LENGTH = 40
        const val MIN_LETTERS = 5
        const val MIN_TOKENS = 3

        private const val WORD_START = "▁"
        private val WHITESPACE = Regex("\\s+")

        // Phone keyboards often type a typographic apostrophe (’) instead of '.
        private val APOSTROPHES = setOf('’', '‘', 'ʼ', '`')

        /**
         * Loads `kws/bpe.model` and `kws/tokens.txt` from the APK.
         * @throws IllegalStateException with a readable message if they are missing or damaged.
         */
        fun fromAssets(context: Context): KeywordEncoder {
            val assets = context.assets
            return try {
                val model = assets.open("$ASSET_DIR/bpe.model").use { it.readBytes() }
                val tokens = assets.open("$ASSET_DIR/tokens.txt").bufferedReader().use { parseTokens(it.readText()) }
                KeywordEncoder(model, tokens)
            } catch (e: IOException) {
                throw IllegalStateException("The wake-word model is missing from this build of Wakey.", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("The wake-word model in this build of Wakey is damaged.", e)
            }
        }

        /** Symbols of a sherpa-onnx `tokens.txt` (`<symbol> <id>` per line). */
        internal fun parseTokens(text: String): Set<String> =
            text.lineSequence().map { it.trim().split(WHITESPACE) }.filter { it.size == 2 }.map { it[0] }.toSet()

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        private fun fail(message: String): Nothing = throw KeywordEncodingException(message)
    }
}
