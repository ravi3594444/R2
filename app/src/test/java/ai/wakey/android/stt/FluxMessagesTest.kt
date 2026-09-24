package ai.wakey.android.stt

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FluxMessagesTest {
    @Test
    fun `parses Connected`() {
        val message = FluxMessage.parse("""{"type":"Connected","request_id":"01a0cee8","sequence_id":0}""")
        assertEquals(FluxMessage.Connected, message)
    }

    @Test
    fun `parses an EndOfTurn with words and languages`() {
        val message = FluxMessage.parse(
            """
            {"type":"TurnInfo","request_id":"r","event":"EndOfTurn","turn_index":0,
             "audio_window_start":0.0,"audio_window_end":1.6,"transcript":"Turn on the flashlight.",
             "words":[{"word":"Turn","confidence":0.97,"start":0.3,"end":0.5},
                      {"word":"flashlight.","confidence":"0.91","start":1.02,"end":1.44}],
             "languages":["en"],"languages_hinted":["en","hi"],"end_of_turn_confidence":0.19,
             "trigger":"manual","sequence_id":8}
            """.trimIndent(),
        )
        assertEquals(FluxMessage.TurnInfo(TurnEvent.EndOfTurn, "Turn on the flashlight.", listOf("en"), 1.44, audioWindowEnd = 1.6, endOfTurnConfidence = 0.19), message)
    }

    @Test
    fun `tolerates missing word timings and languages`() {
        val message = FluxMessage.parse(
            """{"type":"TurnInfo","event":"Update","transcript":"Kholo","words":[{"word":"Kholo","confidence":0.9}],"sequence_id":3}""",
        ) as FluxMessage.TurnInfo
        assertEquals(TurnEvent.Update, message.event)
        assertEquals(emptyList<String>(), message.languages)
        assertNull(message.lastWordEnd)
    }

    @Test
    fun `parses every turn event`() {
        for (event in TurnEvent.entries) {
            val message = FluxMessage.parse("""{"type":"TurnInfo","event":"${event.name}","transcript":"","words":[]}""")
            assertEquals(event, (message as FluxMessage.TurnInfo).event)
        }
    }

    @Test
    fun `unknown turn events and message types are Other`() {
        assertEquals(FluxMessage.Other("TurnInfo"), FluxMessage.parse("""{"type":"TurnInfo","event":"Paused","transcript":""}"""))
        assertEquals(
            FluxMessage.Other("Warning"),
            FluxMessage.parse("""{"type":"Warning","code":"FORCE_END_TURN_NO_ACTIVE_TURN","description":"ignored","sequence_id":1}"""),
        )
    }

    @Test
    fun `parses the fatal Error message`() {
        val message = FluxMessage.parse("""{"type":"Error","sequence_id":4,"code":"INTERNAL_SERVER_ERROR","description":"Something broke"}""")
        assertEquals(FluxMessage.FatalError("INTERNAL_SERVER_ERROR", "Something broke"), message)
        assertEquals("Something broke (INTERNAL_SERVER_ERROR)", (message as FluxMessage.FatalError).readable)
        assertEquals("TIMEOUT", FluxMessage.FatalError("TIMEOUT", "").readable)
    }

    @Test(expected = JSONException::class)
    fun `rejects non-JSON`() {
        FluxMessage.parse("not json")
    }
}
