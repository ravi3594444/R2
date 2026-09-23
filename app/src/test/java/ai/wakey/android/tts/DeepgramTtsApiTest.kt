package ai.wakey.android.tts

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DeepgramTtsApiTest {
    @Test
    fun fluxModelsUseV2WithRawPcm() {
        assertEquals(
            "https://api.deepgram.com/v2/speak?model=flux-meena-en&encoding=linear16&sample_rate=24000&container=none",
            DeepgramTtsApi.speakUrl("flux-meena-en", speed = null).toString(),
        )
    }

    @Test
    fun auraModelsUseV1AndSpeedIsAppended() {
        val url = DeepgramTtsApi.speakUrl("aura-2-thalia-en", speed = "1.25")
        assertEquals("/v1/speak", url.encodedPath)
        assertEquals("aura-2-thalia-en", url.queryParameter("model"))
        assertEquals("none", url.queryParameter("container"))
        assertEquals("1.25", url.queryParameter("speed"))
    }

    @Test
    fun speedIsOmittedAtNormalPaceAndClampedToDeepgramRange() {
        assertNull(DeepgramTtsApi.speedParam(1.0f))
        assertNull(DeepgramTtsApi.speedParam(1.02f))
        assertEquals("1.20", DeepgramTtsApi.speedParam(1.2f))
        assertEquals("0.85", DeepgramTtsApi.speedParam(0.86f))
        assertEquals("1.50", DeepgramTtsApi.speedParam(2.0f))
        assertEquals("0.50", DeepgramTtsApi.speedParam(0.3f))
    }

    @Test
    fun requestBodyIsJsonText() {
        val body = DeepgramTtsApi.requestBody("Theek hai, \"calculator\" khol raha hoon.")
        val buffer = Buffer().also(body::writeTo)
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
        assertEquals("Theek hai, \"calculator\" khol raha hoon.", JSONObject(buffer.readUtf8()).getString("text"))
    }

    @Test
    fun readsBothErrorShapes() {
        assertEquals(
            "INVALID_AUTH",
            DeepgramTtsApi.errorCode("""{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials.","request_id":"x"}"""),
        )
        assertEquals("SPEED_NOT_SUPPORTED", DeepgramTtsApi.errorCode("""{"category":"SPEED_NOT_SUPPORTED","message":"m"}"""))
        assertNull(DeepgramTtsApi.errorCode("Bad gateway"))
        assertNull(DeepgramTtsApi.errorCode("{}"))
    }

    @Test
    fun detectsSpeedNotSupportedOnlyOn400() {
        assertTrue(DeepgramTtsApi.isSpeedUnsupported(400, """{"err_code":"SPEED_NOT_SUPPORTED"}"""))
        assertFalse(DeepgramTtsApi.isSpeedUnsupported(400, """{"err_code":"INVALID_MODEL"}"""))
        assertFalse(DeepgramTtsApi.isSpeedUnsupported(500, "SPEED_NOT_SUPPORTED"))
    }

    @Test
    fun errorMessagesAreReadable() {
        assertEquals("Deepgram rejected the API key (HTTP 401).", DeepgramTtsApi.httpErrorMessage(401, "INVALID_AUTH"))
        assertEquals("Deepgram rejected the API key (HTTP 403).", DeepgramTtsApi.httpErrorMessage(403, null))
        assertEquals("Deepgram's speech service is unavailable (HTTP 503).", DeepgramTtsApi.httpErrorMessage(503, null))
        assertEquals(
            "Deepgram could not speak the reply (HTTP 400, INVALID_MODEL).",
            DeepgramTtsApi.httpErrorMessage(400, "INVALID_MODEL"),
        )
        assertEquals("No internet connection for the Deepgram voice.", DeepgramTtsApi.networkErrorMessage(UnknownHostException("x")))
        assertEquals("Deepgram did not respond in time.", DeepgramTtsApi.networkErrorMessage(SocketTimeoutException()))
        assertEquals("Lost the connection to Deepgram.", DeepgramTtsApi.networkErrorMessage(IOException("reset")))
    }

    @Test
    fun chunksAreSentenceSizedWithinTheRequestLimit() {
        assertEquals(listOf("Flashlight is on."), DeepgramTtsApi.chunks("Flashlight is on."))
        assertEquals(
            listOf("Okay. Opening YouTube now.", "Theek hai, calculator khol raha hoon."),
            DeepgramTtsApi.chunks("Okay. Opening YouTube now. Theek hai, calculator khol raha hoon."),
        )
        val long = "word ".repeat(1_000)
        assertTrue(DeepgramTtsApi.chunks(long).all { it.length <= DeepgramTtsApi.MAX_TEXT_CHARS })
    }
}
