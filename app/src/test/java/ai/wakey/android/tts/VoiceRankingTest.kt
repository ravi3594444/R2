package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRankingTest {
    private fun voice(
        name: String,
        tag: String,
        quality: Int = 400,
        latency: Int = 300,
        network: Boolean = false,
        installed: Boolean = true,
    ) = VoiceInfo(name, tag, quality, latency, network, installed)

    // Shaped like Google's engine: local and network variants per locale, some voice data not downloaded.
    private val voices = listOf(
        voice("en-us-x-sfg-network", "en-US", quality = 500, network = true),
        voice("en-us-x-sfg-local", "en-US"),
        voice("en-in-x-ene-network", "en-IN", quality = 500, network = true),
        voice("en-in-x-ene-local", "en-IN", quality = 300),
        voice("en-in-x-ena-local", "en-IN", quality = 400, latency = 200),
        voice("en-in-x-end-local", "en-IN", quality = 400, latency = 300),
        voice("en-gb-x-gba-local", "en-GB"),
        voice("en-au-x-aua-local", "en-AU"),
        voice("hi-in-x-hia-network", "hi-IN", network = true),
        voice("hi-in-x-hie-local", "hi-IN", installed = false),
        voice("hi-in-x-hid-local", "hi-IN", quality = 300),
        voice("ta-in-x-tag-local", "ta-IN"),
    )

    @Test
    fun targetLanguageFollowsScriptThenHint() {
        assertEquals("hi-IN", VoiceRanking.targetLanguage("ठीक है", null))
        assertEquals("hi-IN", VoiceRanking.targetLanguage("ठीक है", "en-US"))
        assertEquals("hi-IN", VoiceRanking.targetLanguage("Theek hai", "hi-IN"))
        assertEquals("en-IN", VoiceRanking.targetLanguage("Theek hai", null))
        assertEquals("en-US", VoiceRanking.targetLanguage("Hello", "en-US"))
        assertEquals("en-IN", VoiceRanking.targetLanguage("Hello", "en"))
        assertEquals("en-IN", VoiceRanking.targetLanguage("Hola", "es-ES"))
    }

    @Test
    fun englishPrefersOfflineIndianVoiceWithBestQualityThenLatency() {
        val ranked = VoiceRanking.rank(voices, "en-IN").map { it.name }
        assertEquals(
            listOf(
                "en-in-x-ena-local", "en-in-x-end-local", "en-in-x-ene-local", "en-us-x-sfg-local", "en-gb-x-gba-local",
                "en-au-x-aua-local", "en-in-x-ene-network", "en-us-x-sfg-network",
            ),
            ranked,
        )
    }

    @Test
    fun englishHintPutsThatRegionFirst() {
        assertEquals("en-us-x-sfg-local", VoiceRanking.select(voices, "en-US", null)?.name)
        assertEquals("en-gb-x-gba-local", VoiceRanking.select(voices, "en-GB", null)?.name)
    }

    @Test
    fun hindiPrefersInstalledOfflineVoiceAndFallsBackToNetwork() {
        assertEquals("hi-in-x-hid-local", VoiceRanking.select(voices, "hi-IN", null)?.name)
        val onlyNetwork = voices.filterNot { it.name == "hi-in-x-hid-local" }
        assertEquals("hi-in-x-hia-network", VoiceRanking.select(onlyNetwork, "hi-IN", null)?.name)
        assertNull(VoiceRanking.select(voices.filterNot { it.languageTag == "hi-IN" }, "hi-IN", null))
    }

    @Test
    fun preferredVoiceIsHonouredOnlyWhenItFitsTheLanguage() {
        assertEquals("en-gb-x-gba-local", VoiceRanking.select(voices, "en-IN", "en-gb-x-gba-local")?.name)
        assertEquals("en-in-x-ene-network", VoiceRanking.select(voices, "en-IN", "en-in-x-ene-network")?.name)
        // An English pick does not read Hindi text, and voice data that is not installed is never used.
        assertEquals("hi-in-x-hid-local", VoiceRanking.select(voices, "hi-IN", "en-gb-x-gba-local")?.name)
        assertEquals("hi-in-x-hid-local", VoiceRanking.select(voices, "hi-IN", "hi-in-x-hie-local")?.name)
        assertEquals("en-in-x-ena-local", VoiceRanking.select(voices, "en-IN", "no-such-voice")?.name)
    }

    @Test
    fun pickerListsIndianUsUkEnglishAndHindiOfflineFirstWithDistinctLabels() {
        val options = VoiceRanking.options(voices)
        assertEquals(
            listOf(
                "English (India) · offline · high quality · voice 1",
                "English (India) · offline · high quality · voice 2",
                "English (India) · offline · normal quality",
                "English (United States) · offline · high quality",
                "English (United Kingdom) · offline · high quality",
                "Hindi (India) · offline · normal quality",
                "English (India) · needs internet · very high quality",
                "English (United States) · needs internet · very high quality",
                "Hindi (India) · needs internet · high quality",
            ),
            options.map { it.label },
        )
        assertEquals("en-in-x-ena-local", options.first().id)
        assertTrue(options.all { it.engine == TtsEngine.Android })
        assertEquals(listOf(true, false), options.map { it.offline }.distinct())
        assertEquals(options.size, options.map { it.label }.distinct().size)
    }
}
