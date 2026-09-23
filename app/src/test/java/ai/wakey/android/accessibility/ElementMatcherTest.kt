package ai.wakey.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementMatcherTest {
    private val home = parse(settingsHome()).elements
    private val bluetooth = parse(bluetoothPage()).elements

    private fun List<ParsedElement>.find(query: String) = ElementMatcher.find(this, query)?.labels?.firstOrNull()

    @Test
    fun `exact labels match case-insensitively, including merged summaries`() {
        assertEquals("Connected devices", home.find("connected DEVICES"))
        assertEquals("Connected devices", home.find("Bluetooth, pairing"))
        assertEquals("Network & internet", home.find("“Network & internet”"))
    }

    @Test
    fun `dash and quote variants are treated alike`() {
        assertEquals("Network & internet", home.find("Mobile, Wi-Fi, hotspot"))
    }

    @Test
    fun `tappable matches beat plain text with the same label`() {
        val page = parse(screen(textView("Bluetooth", heading = true), row(textView("Bluetooth"), textView("On")))).elements
        assertEquals(2, ElementMatcher.find(page, "Bluetooth")?.id)

        // "Use Bluetooth" contains the query and is a switch; the heading is just text.
        assertEquals("Use Bluetooth", bluetooth.find("Bluetooth"))
    }

    @Test
    fun `a contained label is found when nothing matches exactly`() {
        assertEquals("Connected devices", home.find("connected"))
        assertEquals("Pair new device", bluetooth.find("pair new"))
    }

    @Test
    fun `an exact but disabled control still beats a longer enabled one`() {
        val send = RawNode(className = "android.widget.Button", text = "Send", clickable = true, enabled = false)
        val feedback = RawNode(className = "android.widget.Button", text = "Send feedback", clickable = true)
        val elements = parse(screen(feedback, send)).elements
        assertEquals("Send", elements.find("send"))
    }

    @Test
    fun `short queries match whole words only`() {
        val elements = parse(screen(row(textView("Connected devices")), row(textView("On")))).elements
        assertEquals("On", elements.find("on"))
        assertNull(parse(screen(row(textView("Connected devices")))).elements.find("on"))
    }

    @Test
    fun `a query that wraps the label still finds it`() {
        assertEquals("Pair new device", bluetooth.find("the Pair new device button"))
    }

    @Test
    fun `hints and nearby labels are matched last`() {
        val field = RawNode(className = "android.widget.EditText", editable = true, hint = "Search settings")
        assertEquals(1, ElementMatcher.find(parse(screen(field)).elements, "search settings")?.id)

        val elements = parse(screen(row(textView("Wi‑Fi"), switchWidget(checked = true, clickable = true)))).elements
        assertEquals(1, ElementMatcher.find(elements, "Wi-Fi")?.id)
    }

    @Test
    fun `unknown labels yield nothing and close suggestions`() {
        assertNull(home.find("Battery"))
        assertEquals("Connected devices", ElementMatcher.suggestions(home, "Conected devices").first())
        assertTrue(ElementMatcher.suggestions(bluetooth, "Blutooth").take(2).any { "Bluetooth" in it })
        assertEquals(4, ElementMatcher.suggestions(home, "zzz").size)
    }

    @Test
    fun `matches confirms an id and label that agree`() {
        val connected = home.single { it.labels.firstOrNull() == "Connected devices" }
        assertTrue(ElementMatcher.matches(connected, "Connected devices"))
        assertTrue(ElementMatcher.matches(connected, "bluetooth"))
        assertFalse(ElementMatcher.matches(connected, "Apps"))
    }

    @Test
    fun `keyboard action keys are recognised`() {
        assertTrue(ElementMatcher.isImeActionKey("Search"))
        assertTrue(ElementMatcher.isImeActionKey("Enter"))
        assertTrue(ElementMatcher.isImeActionKey("Go"))
        assertFalse(ElementMatcher.isImeActionKey("Search GIFs"))
        assertFalse(ElementMatcher.isImeActionKey(null))
    }
}
