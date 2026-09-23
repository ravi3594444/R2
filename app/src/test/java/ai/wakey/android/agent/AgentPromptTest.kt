package ai.wakey.android.agent

import ai.wakey.android.agent.AgentPrompt.ReplyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {

    @Test
    fun replyLanguageFollowsTheRequest() {
        for (english in listOf("open Settings and find Bluetooth", "remind me to do it", "tell the band I'm late", "Open Chrome and search for cats")) {
            assertEquals(english, ReplyLanguage.English, ReplyLanguage.of(english))
        }
        for (hinglish in listOf("YouTube kholo aur gaana chalao", "mujhe Bluetooth settings dikhao", "WiFi on karo", "kya time hai")) {
            assertEquals(hinglish, ReplyLanguage.Hinglish, ReplyLanguage.of(hinglish))
        }
        for (hindi in listOf("यूट्यूब खोलो", "Bluetooth चालू करो")) {
            assertEquals(hindi, ReplyLanguage.Hindi, ReplyLanguage.of(hindi))
        }
    }

    @Test
    fun requestStatesTheReplyLanguage() {
        assertEquals("Request: WiFi on karo\nReply language: Hinglish (Hindi written in Latin letters)", AgentPrompt.request("WiFi on karo"))
    }

    @Test
    fun screenTextNamesTheAppAndFlagsProblems() {
        val observation = FakeUi("ai.wakey.android", "Wakey", emptyList(), warning = "Secure window").observation()
            .copy(truncated = true)
        val text = AgentPrompt.screen(observation)
        assertTrue(text, text.startsWith("App: Wakey (ai.wakey.android) - Wakey itself"))
        assertTrue(text.contains("Warning: Secure window"))
        assertTrue(text.contains("No readable elements"))
        assertTrue(text.contains("scroll to see them"))
    }
}
