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
     * Looser sound-alikes for detections that are confirmed before Wakey responds: each word ending
     * in the /i/ sound said with a reduced first vowel ("WAKEY" heard as "WIKY", "WIKI"), as is,
     * run together with the word before it, and after a reduced "HEY". On the desktop evaluation
     * these caught many utterances the phrase and [of] missed; they also match some other speech
     * ("hey Vicky", "a wiki"), which is why they are only candidates.
     */
    fun checksOf(words: List<String>): List<List<String>> {
        val checks = LinkedHashSet<List<String>>()
        for ((i, word) in words.withIndex()) {
            for (reduced in reducedVowelRespellings(word)) {
                val respelled = words.replaced(i, reduced)
                checks += respelled
                if (i > 0) checks += respelled.subList(0, i - 1) + (respelled[i - 1] + reduced) + respelled.subList(i + 1, words.size)
                if (i > 0 && words[i - 1] == "HEY") checks += respelled.replaced(i - 1, "HE")
            }
        }
        checks -= words
        checks.removeAll(of(words).toSet())
        return checks.take(MAX_CHECKS)
    }

    /** Keeps the per-stream beam small; the check lines share it with the phrase and [of]. */
    const val MAX_CHECKS = 6

    /**
     * A word ending in the /i/ sound (WAKEY, BUDDY, SIRI) spelled with the other endings the model
     * emits for that sound. EE is left out: "WAKEE" raised recall in noise but also false alarms.
     */
    private fun finalEeRespellings(word: String): List<String> {
        val ending = finalEeEnding(word) ?: return emptyList()
        val stem = word.dropLast(ending.length)
        return FINAL_EE_ENDINGS.filter { it != ending }.map { stem + it }
    }

    /** WAKEY → WIKY, WIKI: the first vowel reduced to I, with the two short /i/ endings. */
    private fun reducedVowelRespellings(word: String): List<String> {
        val ending = finalEeEnding(word) ?: return emptyList()
        val stem = word.dropLast(ending.length)
        val vowel = FIRST_VOWELS.find(stem) ?: return emptyList()
        if (vowel.value == "I") return emptyList()
        val reduced = stem.replaceRange(vowel.range, "I")
        return REDUCED_ENDINGS.map { reduced + it }
    }

    /** The /i/ ending of [word], or null; short words like HEY, SEE or HI would lose their only vowel. */
    private fun finalEeEnding(word: String): String? {
        if (word.length < MIN_RESPELL_LENGTH) return null
        return FINAL_EE_ENDINGS.firstOrNull { ending ->
            word.endsWith(ending) && (ending.length > 1 || word[word.length - 2] !in VOWELS)
        }
    }

    private fun List<String>.replaced(index: Int, word: String) = toMutableList().also { it[index] = word }

    private const val MIN_RESPELL_LENGTH = 4

    /** In order of how much each helped as a respelling. A lone Y or I must follow a consonant. */
    private val FINAL_EE_ENDINGS = listOf("EY", "Y", "IE", "I")
    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')
    private val REDUCED_ENDINGS = listOf("Y", "I")
    private val FIRST_VOWELS = Regex("[AEIOU]+")
}
