package ai.wakey.android.tts

/** Text preparation shared by the speech engines. Plain Kotlin so it is unit tested on the JVM. */
internal object SpeechText {
    private const val TERMINATORS = ".!?…।॥"
    private const val CLOSERS = "\"'”’)]"
    private const val CLAUSE_BREAKS = ",;:–—"

    /** Lower-case words that end with a dot without ending the sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "a.m", "p.m", "no", "approx",
    )

    /** True when [text] contains Devanagari (U+0900–U+097F), i.e. Hindi script. */
    fun hasDevanagari(text: CharSequence): Boolean = text.any { it in 'ऀ'..'ॿ' }

    /**
     * Splits [text] into sentences for synthesis, with whitespace normalised and line breaks kept.
     *
     * No piece is longer than [maxChars]: over-long sentences break at a clause mark or a space. Consecutive
     * sentences are joined while the piece being built is shorter than [minChars], so "Okay." rides along with the
     * next sentence; [minChars] >= [maxChars] packs greedily up to [maxChars].
     */
    fun split(text: String, maxChars: Int, minChars: Int = 0): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val pieces = text.lines().flatMap { line ->
            val clean = line.trim().replace(WHITESPACE, " ")
            sentences(clean).flatMap { breakLong(it, maxChars) }.mapIndexed { i, s -> Piece(s, startsLine = i == 0) }
        }
        return pack(pieces, maxChars, minChars)
    }

    private val WHITESPACE = Regex("\\s+")

    private class Piece(val text: String, val startsLine: Boolean)

    private fun sentences(line: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            if (line[i] !in TERMINATORS) {
                i++
                continue
            }
            var end = i + 1
            while (end < line.length && (line[end] in TERMINATORS || line[end] in CLOSERS)) end++
            if ((end == line.length || line[end] == ' ') && !isAbbreviation(line, start, i)) {
                out += line.substring(start, end).trim()
                start = end
            }
            i = end
        }
        line.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /** Whether the dot at [dot] closes an abbreviation or an initial ("Dr.", "e.g.", "J.") rather than a sentence. */
    private fun isAbbreviation(line: String, start: Int, dot: Int): Boolean {
        if (line[dot] != '.') return false
        val word = line.substring(maxOf(line.lastIndexOf(' ', dot - 1) + 1, start), dot).trimStart('"', '(', '“', '‘')
        return (word.length == 1 && word[0].isLetter()) || word.lowercase() in ABBREVIATIONS
    }

    private fun breakLong(sentence: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val clause = rest.lastIndexOfAny(CLAUSE_BREAKS.toCharArray(), maxChars - 1)
            var cut = when {
                clause >= maxChars / 2 -> clause + 1
                else -> rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
            }
            if (cut > 1 && rest[cut - 1].isHighSurrogate()) cut--
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun pack(pieces: List<Piece>, maxChars: Int, minChars: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length < minChars && current.length + 1 + piece.text.length <= maxChars) {
                current.append(if (piece.startsLine) '\n' else ' ').append(piece.text)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current.setLength(0)
                current.append(piece.text)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
