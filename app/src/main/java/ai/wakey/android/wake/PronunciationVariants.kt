package ai.wakey.android.wake

/**
 * Respellings of a wake phrase that match how the KWS model transcribes it when people say it:
 * "HEY WAKEY" often comes out as "HEY WAKY", "HEY WAKIE", "HEYWAKEY" or "HE WAKEY", especially
 * from fast or accented (e.g. Indian English) speakers. Spotting these alongside the phrase itself,
 * under the phrase's tag, catches those utterances.
 *
 * The rules are generic spelling patterns, so a custom phrase benefits too; each was kept only if it
 * raised recall on the desktop evaluation without adding false alarms. Each variant applies one rule
 * once, which keeps the list short.
 */
internal object PronunciationVariants {
    /** Keeps the keyword graph and the per-stream beam small. */
    const val MAX_VARIANTS = 6

    /** @param words the normalised, upper-case words of the phrase. */
    fun of(words: List<String>): List<List<String>> {
        val variants = LinkedHashSet<List<String>>()
        for ((i, word) in words.withIndex()) {
            for (respelled in finalEeRespellings(word)) variants += words.replaced(i, respelled)
        }
        // Run-together words: the model often hears "HEYWAKEY" or "OKGOOGLE" as one word.
        for (i in 0 until words.size - 1) {
            variants += words.subList(0, i) + (words[i] + words[i + 1]) + words.subList(i + 2, words.size)
        }
        // A reduced "hey", heard as "he".
        for ((i, word) in words.withIndex()) if (word == "HEY") variants += words.replaced(i, "HE")
        variants -= words
        return variants.take(MAX_VARIANTS)
    }

    /**
     * A word ending in the /i/ sound (WAKEY, BUDDY, SIRI) spelled with the other endings the model
     * emits for that sound. EE is left out: "WAKEE" raised recall in noise but also false alarms.
     */
    private fun finalEeRespellings(word: String): List<String> {
        // Short words like HEY, SEE or HI would lose their only vowel.
        if (word.length < MIN_RESPELL_LENGTH) return emptyList()
        val ending = FINAL_EE_ENDINGS.firstOrNull { ending ->
            word.endsWith(ending) && (ending.length > 1 || word[word.length - 2] !in VOWELS)
        } ?: return emptyList()
        val stem = word.dropLast(ending.length)
        return FINAL_EE_ENDINGS.filter { it != ending }.map { stem + it }
    }

    private fun List<String>.replaced(index: Int, word: String) = toMutableList().also { it[index] = word }

    private const val MIN_RESPELL_LENGTH = 4

    /** In order of how much each helped as a respelling. A lone Y or I must follow a consonant. */
    private val FINAL_EE_ENDINGS = listOf("EY", "Y", "IE", "I")
    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')
}
