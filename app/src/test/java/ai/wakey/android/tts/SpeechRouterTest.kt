package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRouterTest {
    private var clock = 1_000L
    private val router = SpeechRouter(now = { clock })

    private fun route(text: String, engine: TtsEngine = TtsEngine.Deepgram, hasKey: Boolean = true) =
        router.route(engine, text, hasKey)

    @Test
    fun androidSelectionAlwaysUsesAndroidWithoutAReason() {
        assertEquals(Route.Android(null), route("Flashlight is on.", TtsEngine.Android))
        assertEquals(Route.Android(null), route("टॉर्च चालू है।", TtsEngine.Android))
    }

    @Test
    fun romanisedHinglishStaysOnDeepgram() {
        assertEquals(Route.Deepgram, route("Theek hai, calculator khol raha hoon."))
        assertEquals(Route.Deepgram, route("Hi, I'm Wakey. Namaste! Kya madad karun?"))
    }

    @Test
    fun devanagariGoesToAndroid() {
        assertEquals(Route.Android(SpeechRouter.HINDI_REASON), route("ठीक है, calculator खोल रहा हूँ।"))
        assertEquals(Route.Android(SpeechRouter.HINDI_REASON), route("Okay। Done"))
    }

    @Test
    fun missingKeyGoesToAndroidWithoutCooldown() {
        assertEquals(Route.Android(SpeechRouter.NO_KEY_REASON), route("Hello", hasKey = false))
        assertEquals(Route.Deepgram, route("Hello"))
    }

    @Test
    fun failureSkipsDeepgramForTheCooldownThenRetries() {
        router.deepgramFailed("Deepgram did not respond in time.")
        val during = route("Hello")
        assertTrue(during is Route.Android)
        assertEquals(
            "Deepgram did not respond in time. Using the Android voice; retrying Deepgram in 60 s.",
            (during as Route.Android).reason,
        )
        clock += 59_001
        assertEquals("retrying Deepgram in 1 s.", (route("Hello") as Route.Android).reason?.substringAfter("voice; "))
        clock += 999
        assertEquals(Route.Deepgram, route("Hello"))
    }

    @Test
    fun hindiRoutingIsUnaffectedByCooldownAndSuccessClearsIt() {
        router.deepgramFailed("Lost the connection to Deepgram.")
        assertEquals(Route.Android(SpeechRouter.HINDI_REASON), route("नमस्ते"))
        router.deepgramSucceeded()
        assertEquals(Route.Deepgram, route("Hello"))
    }
}
