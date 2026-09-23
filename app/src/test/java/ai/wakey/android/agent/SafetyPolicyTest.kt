package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {
    private fun tap(label: String, sensitive: Boolean = false, reason: String? = null) =
        AgentAction.Tap(ElementTarget(id = 1), element(1, label), sensitive, reason)

    @Test
    fun consequentialLabelsNeedConfirmation() {
        val labels = listOf(
            "Send", "SEND", "Pay ₹500", "Place order", "Buy now", "Proceed to checkout", "Delete", "Remove account",
            "Uninstall", "Factory reset", "Sign out", "Log out", "Change password", "Subscribe", "Book ticket",
            "Post", "Share", "Call", "Transfer money", "भेजें", "संदेश भेजो", "अभी खरीदें", "भुगतान करें",
            "पेमेंट करें", "Bhejo",
        )
        for (label in labels) assertNotNull(label, SafetyPolicy.review(tap(label), "App"))
    }

    @Test
    fun ordinaryLabelsRunDirectly() {
        val labels = listOf(
            "Bluetooth", "Network & internet", "Connected devices", "Facebook", "फेसबुक", "Calls", "Search settings",
            "Sender info", "Display", "Wallpaper & style", "Orders placed", "Message",
        )
        for (label in labels) assertNull(label, SafetyPolicy.review(tap(label), "Settings"))
    }

    @Test
    fun modelFlagAloneIsEnough() {
        val request = SafetyPolicy.review(AgentAction.Scroll(ScrollDirection.Down, null, true, "Accept the terms"), "Paytm")
        assertEquals("Scroll down in Paytm?", request?.question)
        assertEquals("Accept the terms", request?.detail)
        assertNotNull(SafetyPolicy.review(AgentAction.OpenApp("Banking", sensitive = true, reason = null), null))
        assertNull(SafetyPolicy.review(AgentAction.GoBack(sensitive = false, reason = null), null))
    }

    @Test
    fun typedTextAndItsFieldAreChecked() {
        val search = AgentAction.EnterText("cute cats", ElementTarget(id = 2), element(2, "Search"), submit = true, sensitive = false, reason = null)
        assertNull(SafetyPolicy.review(search, "YouTube"))

        val password = AgentAction.EnterText("hunter2", ElementTarget(id = 3), element(3, null, hint = "Password"), submit = false, sensitive = false, reason = null)
        val request = SafetyPolicy.review(password, "Gmail")
        assertEquals("Type “hunter2” in Gmail?", request?.question)
        assertEquals("Text: hunter2", request?.detail)
    }

    @Test
    fun questionNamesTheElementAndApp() {
        val request = SafetyPolicy.review(tap("Send", reason = "Send “hi” to Priya"), "WhatsApp")
        assertEquals("Tap “Send” in WhatsApp?", request?.question)
        assertEquals("Send “hi” to Priya", request?.detail)
        assertEquals("Tap “Send”", SafetyPolicy.review(tap("Send"), null)?.detail)
    }

    @Test
    fun keywordMatchingUsesWordBoundaries() {
        assertTrue(SafetyPolicy.isConsequential("Tap to pay"))
        assertFalse(SafetyPolicy.isConsequential("Paytm"))
        assertFalse(SafetyPolicy.isConsequential("Notebook"))
        assertFalse(SafetyPolicy.isConsequential("Recall"))
    }
}
