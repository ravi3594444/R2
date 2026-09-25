package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Checks the on-device encoder against Python sentencepiece with the real KWS model files.
 * Expected tokens are `spm.SentencePieceProcessor(model_file="bpe.model").encode(p.upper(), out_type=str)`
 * with sentencepiece 0.2.x.
 */
class KeywordEncoderTest {
    private lateinit var encoder: KeywordEncoder
    private lateinit var tokens: Set<String>
    private lateinit var modelBytes: ByteArray

    @Before
    fun setUp() {
        val dir = MODEL_DIRS.map(::File).firstOrNull { File(it, "bpe.model").isFile && File(it, "tokens.txt").isFile }
        assumeTrue("KWS model files not found in $MODEL_DIRS", dir != null)
        tokens = KeywordEncoder.parseTokens(File(dir, "tokens.txt").readText())
        modelBytes = File(dir, "bpe.model").readBytes()
        encoder = KeywordEncoder(modelBytes, tokens)
    }

    @Test
    fun `matches python sentencepiece`() {
        val mismatches = SENTENCEPIECE_REFERENCE.mapNotNull { (phrase, expected) ->
            val actual = encoder.encode(phrase).tokens.joinToString(" ")
            if (actual == expected) null else "$phrase: expected [$expected] got [$actual]"
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
        assertEquals(48, SENTENCEPIECE_REFERENCE.size)
    }

    @Test
    fun `normalises case, spacing and typographic apostrophes`() {
        val keyword = encoder.encode("  what’s   UP wakey ")
        assertEquals("WHAT'S UP WAKEY", keyword.phrase)
        assertEquals("WHAT'S_UP_WAKEY", keyword.tag)
        assertEquals(encoder.encode("what's up wakey"), keyword)
    }

    @Test
    fun `default phrase gives the keywords validated on desktop`() {
        val scoring = SherpaWakeWordDetector.scoringFor(0.5f)
        assertEquals(
            listOf(
                "▁HE Y ▁WA KE Y", "▁HE Y ▁WA K Y", "▁HE Y ▁WA K I E", "▁HE Y ▁WA K I", "▁HE Y W A KE Y", "▁HE ▁WA KE Y",
            ).joinToString("/") { "$it :1.50 #0.18 @HEY_WAKEY" },
            encoder.encode("Hey Wakey").toSherpaKeywords(scoring.boost, scoring.threshold),
        )
    }

    @Test
    fun `default phrase adds check lines under their own tag`() {
        val keyword = encoder.encode("Hey Wakey")
        // spm.encode("HEY WIKY"), spm.encode("HEYWIKY"), ...
        assertEquals(
            listOf("▁HE Y ▁W I K Y", "▁HE Y W I K Y", "▁HE ▁W I K Y", "▁HE Y ▁W I K I", "▁HE Y W I K I", "▁HE ▁W I K I"),
            keyword.checkVariants.map { it.joinToString(" ") },
        )
        val line = keyword.toSherpaKeywords(1.5f, 0.18f, KeywordScoring(1.5f, 0.10f))
        assertEquals(12, line.split('/').size)
        assertTrue(line.endsWith("/▁HE ▁W I K I :1.50 #0.10 @HEY_WAKEY__CHECK"))
        assertEquals(SherpaWakeWordDetector.scoringFor(0.5f).threshold - 0.08f, SherpaWakeWordDetector.checkScoringFor(0.5f).threshold, 1e-6f)
    }

    @Test
    fun `hey mode spots hey wakey and checks hey plus a command word`() {
        val keyword = encoder.encode("Hey Wakey")
        val hey = encoder.encodeHey()
        assertEquals(keyword.tokens, hey.tokens)
        assertEquals(keyword.variants, hey.variants)
        val checks = hey.checkVariants.map { it.joinToString(" ") }
        // spm.encode("HEY OPEN"), spm.encode("HEY INSTAGRAM"), spm.encode("HEY TORCH")
        assertTrue(checks.containsAll(listOf("▁HE Y ▁O P EN", "▁HE Y ▁IN S TA G RA M", "▁HE Y ▁TO R CH", "▁HE Y ▁W I K Y")))
        assertTrue(checks.size >= KeywordEncoder.HEY_COMMAND_WORDS.size)
        assertEquals(checks, checks.distinct())
        assertTrue(hey.checkVariants.none { it == hey.tokens || it in hey.variants })
    }

    @Test
    fun `variant tokens match python sentencepiece for the respelled phrase`() {
        // spm.encode("HEY BUDDEY") etc.; the phrase and its tag stay the user's.
        val keyword = encoder.encode("hey buddy")
        assertEquals("HEY_BUDDY", keyword.tag)
        assertEquals(
            listOf("▁HE Y ▁BU D DE Y", "▁HE Y ▁BU D DI E", "▁HE Y ▁BU D DI", "▁HE Y B U D D Y", "▁HE ▁BU D D Y"),
            keyword.variants.map { it.joinToString(" ") },
        )
        assertEquals(
            listOf("▁HE LL O CO M P U TER"),
            encoder.encode("Hello Computer").variants.map { it.joinToString(" ") },
        )
        assertEquals(
            listOf(
                "▁WHAT ' S ▁UP ▁WA K Y", "▁WHAT ' S ▁UP ▁WA K I E", "▁WHAT ' S ▁UP ▁WA K I",
                "▁WHAT ' S U P ▁WA KE Y", "▁WHAT ' S ▁UP W A KE Y",
            ),
            encoder.encode("what's up wakey").variants.map { it.joinToString(" ") },
        )
    }

    @Test
    fun `variants use only tokens from tokens txt`() {
        val limited = KeywordEncoder(modelBytes, tokens - "DI")
        val variants = limited.encode("hey buddy").variants.map { it.joinToString(" ") }
        assertEquals(listOf("▁HE Y ▁BU D DE Y", "▁HE Y B U D D Y", "▁HE ▁BU D D Y"), variants)
    }

    @Test
    fun `every produced token is in tokens txt`() {
        assertEquals(500, tokens.size)
        SENTENCEPIECE_REFERENCE.forEach { (phrase, _) -> assertTrue(encoder.encode(phrase).tokens.all { it in tokens }) }
    }

    @Test
    fun `rejects phrases with readable messages`() {
        assertRejected("", "Enter a wake phrase.")
        assertRejected("   ", "Enter a wake phrase.")
        assertRejected("Hey Wakey!", "Use only English letters, spaces and apostrophes (remove “!”).")
        assertRejected("Hey, Wakey", "Use only English letters, spaces and apostrophes (remove “,”).")
        assertRejected("héy wakey", "Use only English letters, spaces and apostrophes (remove “é”).")
        assertRejected("Hey Robot 2", "Spell numbers out as words (found “2”).")
        assertRejected("one two three four five six seven", "Use at most 6 words.")
        assertRejected("abcdefghij abcdefghij abcdefghij abcdefghij", "Use at most 40 characters.")
        assertRejected("hey ' wakey", "“'” is not a word.")
        assertRejected("Hi", "“Hi” is too short to detect reliably. Use a longer phrase, like “Hey Wakey”.")
        assertRejected("hey", "“hey” is too short to detect reliably. Use a longer phrase, like “Hey Wakey”.")
        // Ten letters but a single vocabulary piece: matches ordinary speech too easily.
        assertRejected("Everything", "“Everything” is too short to detect reliably. Use a longer phrase, like “Hey Wakey”.")
    }

    @Test
    fun `accepts short but distinctive phrases`() {
        assertEquals(listOf("▁A", "LE", "X", "A"), encoder.encode("Alexa").tokens)
        assertEquals(listOf("▁WA", "KE", "▁UP"), encoder.encode("wake up").tokens)
    }

    @Test
    fun `rejects tokens missing from tokens txt`() {
        val limited = KeywordEncoder(modelBytes, tokens - "KE")
        val error = assertThrows(KeywordEncodingException::class.java) { limited.encode("hey wakey") }
        assertEquals("“hey wakey” can't be spelled with the wake-word model's vocabulary (missing “KE”).", error.message)
    }

    @Test
    fun `parses tokens txt`() {
        assertEquals(setOf("<blk>", "▁", "'"), KeywordEncoder.parseTokens("<blk> 0\n▁ 20\n' 13\n\n"))
    }

    private fun assertRejected(phrase: String, message: String) {
        val error = assertThrows(KeywordEncodingException::class.java) { encoder.encode(phrase) }
        assertEquals(message, error.message)
    }

    private companion object {
        /** The build extracts the model here (Gradle task prepareModelAssets); the desktop copy is a fallback. */
        val MODEL_DIRS = listOf(
            "build/generated/wakeyAssets/kws",
            "/opt/wakey-deps/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile",
        )

        val SENTENCEPIECE_REFERENCE = listOf(
            "hey wakey" to "▁HE Y ▁WA KE Y",
            "ok wakey" to "▁O K ▁WA KE Y",
            "okay wakey" to "▁OKAY ▁WA KE Y",
            "hello computer" to "▁HE LL O ▁COMP U TER",
            "hey jarvis" to "▁HE Y ▁JA R VI S",
            "wake up buddy" to "▁WA KE ▁UP ▁BU D D Y",
            "hey assistant" to "▁HE Y ▁AS S IST ANT",
            "Hey Wakey" to "▁HE Y ▁WA KE Y",
            "HEY WAKEY" to "▁HE Y ▁WA KE Y",
            "hEy WaKeY" to "▁HE Y ▁WA KE Y",
            "  hey   wakey  " to "▁HE Y ▁WA KE Y",
            "hello wakey" to "▁HE LL O ▁WA KE Y",
            "hi wakey" to "▁HI ▁WA KE Y",
            "wakey wakey" to "▁WA KE Y ▁WA KE Y",
            "hey siri" to "▁HE Y ▁S I RI",
            "alexa" to "▁A LE X A",
            "ok google" to "▁O K ▁GO O G LE",
            "hey google" to "▁HE Y ▁GO O G LE",
            "hello world" to "▁HE LL O ▁WORLD",
            "computer" to "▁COMP U TER",
            "jarvis" to "▁JA R VI S",
            "hey buddy" to "▁HE Y ▁BU D D Y",
            "what's up wakey" to "▁WHAT ' S ▁UP ▁WA KE Y",
            "let's go wakey" to "▁LET ' S ▁GO ▁WA KE Y",
            "wakey's here" to "▁WA KE Y ' S ▁HERE",
            "o'reilly" to "▁O ' RE IL LY",
            "don't stop" to "▁DON ' T ▁ST O P",
            "rock 'n' roll" to "▁RO CK ▁ ' N ' ▁RO LL",
            "hey friday" to "▁HE Y ▁F RI DAY",
            "good morning wakey" to "▁GOOD ▁MO R N ING ▁WA KE Y",
            "listen up wakey" to "▁LI S T EN ▁UP ▁WA KE Y",
            "hey mycroft" to "▁HE Y ▁MY C RO F T",
            "hey robot" to "▁HE Y ▁RO B O T",
            "yo wakey" to "▁ Y O ▁WA KE Y",
            "hey there" to "▁HE Y ▁THERE",
            "okay computer" to "▁OKAY ▁COMP U TER",
            "hey chanakya" to "▁HE Y ▁CHA N A K Y A",
            "namaste wakey" to "▁NA MA S TE ▁WA KE Y",
            "suno wakey" to "▁SU N O ▁WA KE Y",
            "hey arjun" to "▁HE Y ▁A R J UN",
            "hey priya" to "▁HE Y ▁P RI Y A",
            "hey wakey wakey" to "▁HE Y ▁WA KE Y ▁WA KE Y",
            "wakey" to "▁WA KE Y",
            "hey you there" to "▁HE Y ▁YOU ▁THERE",
            "wake up" to "▁WA KE ▁UP",
            "hello there computer" to "▁HE LL O ▁THERE ▁COMP U TER",
            "hey kitt" to "▁HE Y ▁K IT T",
            "abracadabra" to "▁A B RA C AD A B RA",
        )
    }
}
