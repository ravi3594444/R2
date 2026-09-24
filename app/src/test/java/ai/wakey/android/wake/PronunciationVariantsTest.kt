package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationVariantsTest {
    @Test
    fun `hey wakey gets respelled endings, run-together words and a reduced hey`() {
        assertEquals(
            listOf("HEY WAKY", "HEY WAKIE", "HEY WAKI", "HEYWAKEY", "HE WAKEY"),
            variants("HEY WAKEY"),
        )
    }

    @Test
    fun `rules are generic, not tied to the default phrase`() {
        assertEquals(listOf("HEY BUDDEY", "HEY BUDDIE", "HEY BUDDI", "HEYBUDDY", "HE BUDDY"), variants("HEY BUDDY"))
        assertEquals(listOf("OK SIREY", "OK SIRY", "OK SIRIE", "OKSIRI"), variants("OK SIRI"))
        assertEquals(listOf("HELLOCOMPUTER"), variants("HELLO COMPUTER"))
        assertEquals(listOf("WAKY", "WAKIE", "WAKI"), variants("WAKEY"))
    }

    @Test
    fun `short words and vowel-y endings keep their spelling`() {
        // HEY, SEE and HI are too short to respell; in PLAY and ENJOY the Y belongs to a vowel.
        assertEquals(listOf("HISEE"), variants("HI SEE"))
        assertEquals(listOf("PLAYNOW"), variants("PLAY NOW"))
        assertEquals(listOf("ENJOYIT"), variants("ENJOY IT"))
    }

    @Test
    fun `a long phrase keeps the first variants up to the cap`() {
        val result = PronunciationVariants.of("HEY WAKEY WAKE UP BUDDY".split(' '))
        assertEquals(PronunciationVariants.MAX_VARIANTS, result.size)
        assertEquals(listOf("HEY", "WAKY", "WAKE", "UP", "BUDDY"), result.first())
    }

    @Test
    fun `never returns the phrase itself or duplicates`() {
        for (phrase in listOf("HEY WAKEY", "HEY HEY", "WAKEY WAKEY", "ALEXA")) {
            val words = phrase.split(' ')
            val result = PronunciationVariants.of(words)
            assertTrue(phrase, words !in result)
            assertEquals(phrase, result.distinct(), result)
        }
    }

    @Test
    fun `check spellings reduce the first vowel of an ee-ending word`() {
        assertEquals(
            listOf("HEY WIKY", "HEYWIKY", "HE WIKY", "HEY WIKI", "HEYWIKI", "HE WIKI"),
            checks("HEY WAKEY"),
        )
        assertEquals(listOf("HEY BIDDY", "HEYBIDDY", "HE BIDDY", "HEY BIDDI", "HEYBIDDI", "HE BIDDI"), checks("HEY BUDDY"))
        assertEquals(listOf("WIKY", "WIKI"), checks("WAKEY"))
    }

    @Test
    fun `phrases without such a word get no check spellings`() {
        assertEquals(emptyList<String>(), checks("HELLO COMPUTER"))
        // SIRI's first vowel is already I.
        assertEquals(emptyList<String>(), checks("OK SIRI"))
    }

    @Test
    fun `check spellings never repeat the phrase or its variants`() {
        for (phrase in listOf("HEY WAKEY", "WAKEY WAKEY", "HEY WIKY")) {
            val words = phrase.split(' ')
            val result = PronunciationVariants.checksOf(words)
            assertTrue(phrase, words !in result)
            assertTrue(phrase, result.none { it in PronunciationVariants.of(words) })
            assertTrue(result.size <= PronunciationVariants.MAX_CHECKS)
        }
    }

    private fun checks(phrase: String) = PronunciationVariants.checksOf(phrase.split(' ')).map { it.joinToString(" ") }

    private fun variants(phrase: String) = PronunciationVariants.of(phrase.split(' ')).map { it.joinToString(" ") }
}
