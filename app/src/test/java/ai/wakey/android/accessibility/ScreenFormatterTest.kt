package ai.wakey.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFormatterTest {

    private fun observe(
        root: RawNode,
        packageName: String? = SETTINGS,
        appLabel: String? = "Settings",
        keyboardOpen: Boolean = false,
        notice: ScreenNotice? = null,
    ): ScreenObservation {
        val windows = listOf(RawWindow(packageName, root))
        return ScreenFormatter.observation(packageName, appLabel, windows, ScreenParser.parse(windows, 150), keyboardOpen, notice)
    }

    @Test
    fun `text is headed by app label and package with one line per element`() {
        val observation = observe(settingsHome())

        assertEquals(
            """
            App: Settings (com.android.settings)
            [1] heading "Settings"
            [2] item "Search settings" (tap)
            [3] list id=recycler_view near "Network & internet" (scroll)
            [4] item "Network & internet" – "Mobile, Wi‑Fi, hotspot" (tap)
            [5] item "Connected devices" – "Bluetooth, pairing" (tap)
            [6] item "Apps" – "Assistant, recent apps, default apps" (tap)
            """.trimIndent(),
            observation.text,
        )
        assertEquals(6, observation.elements.size)
        assertEquals(SETTINGS, observation.packageName)
        assertNull(observation.warning)
    }

    @Test
    fun `states, selection, disabled and unlabeled controls are rendered`() {
        val tab = RawNode(className = "androidx.appcompat.app.ActionBar\$Tab", text = "Wi‑Fi", clickable = true, selected = true)
        val send = RawNode(className = "android.widget.Button", text = "Send", clickable = true, enabled = false)
        val more = RawNode(className = "android.widget.ImageButton", clickable = true, viewId = "com.example:id/overflow")
        val check = RawNode(className = "android.widget.CheckBox", text = "Remember me", clickable = true, checkable = true, checked = true)
        val lines = parse(screen(tab, send, more, check)).lines()

        assertEquals(
            listOf(
                "[1] tab \"Wi‑Fi\" (selected) (tap)",
                "[2] button \"Send\" (disabled) (tap)",
                "[3] button id=overflow near \"Wi‑Fi\" (tap)",
                "[4] checkbox \"Remember me\" (checked) (tap)",
            ),
            lines,
        )
    }

    @Test
    fun `double quotes inside labels cannot break the line format`() {
        val line = parse(screen(textView("Say \"hi\""))).lines().single()
        assertEquals("[1] text \"Say 'hi'\"", line)
    }

    @Test
    fun `signature ignores bounds jitter but follows content and state`() {
        val base = observe(settingsHome()).signature

        assertEquals(base, observe(settingsHome(shift = 37)).signature)
        assertEquals(16, base.length)
        assertNotEquals(base, observe(settingsHome(), packageName = "com.other").signature)
        assertNotEquals(observe(bluetoothPage(on = false)).signature, observe(bluetoothPage(on = true)).signature)
        assertNotEquals(base, observe(screen(textView("Settings", heading = true))).signature)
    }

    @Test
    fun `an empty tree warns and suggests a screenshot`() {
        val observation = observe(screen())

        assertTrue(observation.elements.isEmpty())
        val warning = observation.warning!!
        assertTrue("screenshot" in warning)
        assertTrue(observation.text.endsWith("Note: $warning"))
    }

    @Test
    fun `lock screen and Wakey-in-front notices take precedence`() {
        assertTrue("locked" in observe(settingsHome(), notice = ScreenNotice.Locked).warning!!)
        val wakey = observe(screen(), packageName = "ai.wakey.android", appLabel = "Wakey", notice = ScreenNotice.WakeyInForeground)
        assertTrue("Wakey itself" in wakey.warning!!)
        assertTrue(wakey.text.startsWith("App: Wakey (ai.wakey.android)"))
    }

    @Test
    fun `graphics surfaces add a softer warning while keeping the elements`() {
        val map = RawNode(className = "android.view.TextureView", bounds = box(0, 300, 1080, 2000))
        val observation = observe(screen(textView("Directions"), map))

        assertEquals(1, observation.elements.size)
        assertTrue("graphics" in observation.warning!!)
    }

    @Test
    fun `keyboard, truncation and popups are called out`() {
        val popup = RawWindow("com.android.chrome", screen(row(textView("cats"), top = 300), row(textView("cat videos"), top = 460)))
        val main = RawWindow("com.android.chrome", screen(textView("New tab")))
        val windows = listOf(popup, main)
        val parsed = ScreenParser.parse(windows, maxElements = 2)
        val text = ScreenFormatter.observation("com.android.chrome", "Chrome", windows, parsed, keyboardOpen = true).text

        assertEquals(
            """
            App: Chrome (com.android.chrome)
            Keyboard is open.
            [1] item "cats" (tap)
            [2] item "cat videos" (tap)
            (More elements not shown; scroll or target a specific label.)
            """.trimIndent(),
            text,
        )

        val all = ScreenFormatter.observation("com.android.chrome", "Chrome", windows, ScreenParser.parse(windows, 150), false).text
        assertTrue("-- Popup (com.android.chrome) --\n[1] item \"cats\"" in all)
        assertTrue("-- Main window --\n[3] text \"New tab\"" in all)
    }

    @Test
    fun `unknown app label falls back to the package`() {
        assertTrue(observe(settingsHome(), appLabel = null).text.startsWith("App: com.android.settings\n"))
        assertFalse(observe(settingsHome(), packageName = null, appLabel = null).text.contains("null"))
    }
}
