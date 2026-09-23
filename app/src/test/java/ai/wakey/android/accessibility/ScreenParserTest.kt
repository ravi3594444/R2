package ai.wakey.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenParserTest {

    @Test
    fun `settings rows carry their child title and summary and the children are not listed again`() {
        val parsed = parse(settingsHome())

        assertEquals(
            listOf(
                "[1] heading \"Settings\"",
                "[2] item \"Search settings\" (tap)",
                "[3] list id=recycler_view near \"Network & internet\" (scroll)",
                "[4] item \"Network & internet\" – \"Mobile, Wi‑Fi, hotspot\" (tap)",
                "[5] item \"Connected devices\" – \"Bluetooth, pairing\" (tap)",
                "[6] item \"Apps\" – \"Assistant, recent apps, default apps\" (tap)",
            ),
            parsed.lines(),
        )
        val connected = parsed.elements[4].element
        assertEquals("Connected devices – Bluetooth, pairing", connected.text)
        assertTrue(connected.clickable)
        assertFalse(parsed.truncated)
    }

    @Test
    fun `a row holding a plain switch becomes that switch with its state`() {
        val parsed = parse(bluetoothPage(on = false))

        val useBluetooth = parsed.elements.single { "Use Bluetooth" in it.labels }
        assertEquals("switch", useBluetooth.element.role)
        assertEquals(false, useBluetooth.element.checked)
        assertEquals(listOf("Use Bluetooth"), useBluetooth.labels)
        assertEquals(5, parsed.elements.size)
        assertEquals("[3] switch \"Use Bluetooth\" (off) (tap)", ScreenFormatter.line(useBluetooth))
    }

    @Test
    fun `actionable children of a row stay separate and borrow the row's label`() {
        val parsed = parse(screen(row(textView("Wi‑Fi"), textView("Home"), switchWidget(checked = true, clickable = true))))

        assertEquals(
            listOf(
                "[1] item \"Wi‑Fi\" – \"Home\" (tap)",
                "[2] switch near \"Wi‑Fi\" (on) (tap)",
            ),
            parsed.lines(),
        )
    }

    @Test
    fun `child labels already in the row description are dropped but short words are not over-matched`() {
        val described = parse(screen(row(textView("Bluetooth"), textView("On"), description = "Bluetooth, On")))
        assertEquals(listOf("Bluetooth, On"), described.elements.single().labels)

        val status = parse(screen(row(textView("Connected"), textView("On"))))
        assertEquals(listOf("Connected", "On"), status.elements.single().labels)
    }

    @Test
    fun `rows with many labels are left unmerged so their texts stay readable`() {
        val card = row(*Array(6) { textView("Line $it") })
        val parsed = parse(screen(card))

        assertEquals(7, parsed.elements.size)
        assertEquals("[1] item near \"Line 0\" (tap)", ScreenFormatter.line(parsed.elements[0]))
        assertEquals((0 until 6).map { "Line $it" }, parsed.elements.drop(1).map { it.labels.single() })
    }

    @Test
    fun `invisible nodes and their subtrees are skipped`() {
        val hidden = RawNode(visible = false, children = listOf(textView("Hidden page")), bounds = box(0, 0, 10, 10))
        val parsed = parse(screen(textView("Shown"), hidden))

        assertEquals(listOf("Shown"), parsed.elements.map { it.labels.single() })
    }

    @Test
    fun `fields show value and hint but never a password or a hint posing as text`() {
        val empty = RawNode(
            className = "android.widget.EditText",
            editable = true,
            clickable = true,
            text = "Search settings",
            showingHint = true,
            contentDescription = "Search",
            bounds = box(0, 0, 1080, 120),
        )
        val filled = RawNode(
            className = "android.widget.EditText",
            editable = true,
            focused = true,
            text = "cats",
            hint = "Search or type URL",
            bounds = box(0, 200, 1080, 320),
        )
        val password = RawNode(
            className = "android.widget.EditText",
            editable = true,
            password = true,
            text = "hunter2",
            hint = "Password",
            bounds = box(0, 400, 1080, 520),
        )
        val parsed = parse(screen(empty, filled, password))

        assertEquals(
            listOf(
                "[1] input \"Search\" hint=\"Search settings\" (type)",
                "[2] input value=\"cats\" hint=\"Search or type URL\" (focused) (type)",
                "[3] input hint=\"Password\" (password) (type)",
            ),
            parsed.lines(),
        )
        assertNull(parsed.elements[0].element.text)
        assertEquals("cats", parsed.elements[1].element.text)
        assertNull(parsed.elements[2].element.text)
        assertFalse(parsed.elements.any { e -> e.labels.any { "hunter2" in it } })
    }

    @Test
    fun `over the limit, actionable elements are kept first and reading order is preserved`() {
        val texts = (0 until 30).map { textView("Paragraph $it", top = it * 60) }
        val buttons = (0 until 5).map {
            RawNode(className = "android.widget.Button", text = "Action $it", clickable = true, bounds = box(0, 2000, 200, 2100))
        }
        val parsed = parse(screen(*(texts + buttons).toTypedArray()), maxElements = 10)

        assertTrue(parsed.truncated)
        assertEquals((1..10).toList(), parsed.elements.map { it.id })
        assertEquals(
            (0 until 5).map { "Paragraph $it" } + (0 until 5).map { "Action $it" },
            parsed.elements.map { it.labels.single() },
        )
    }

    @Test
    fun `a cut-short tree read is reported as truncated`() {
        val parsed = ScreenParser.parse(listOf(RawWindow(SETTINGS, settingsHome())), 150, readTruncated = true)
        assertTrue(parsed.truncated)
    }

    @Test
    fun `labels are whitespace-collapsed and capped`() {
        val parsed = parse(screen(textView("Line one\n   line two​"), textView("x".repeat(200))))

        assertEquals("Line one line two", parsed.elements[0].labels.single())
        val long = parsed.elements[1].labels.single()
        assertEquals(ScreenParser.MAX_LABEL_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `roles come from class names, role descriptions and flags`() {
        fun role(className: String? = null, roleDescription: String? = null, node: RawNode = RawNode()) =
            ScreenParser.roleOf(node.copy(className = className ?: node.className, roleDescription = roleDescription))

        assertEquals("button", role("android.widget.Button"))
        assertEquals("button", role("android.widget.ImageButton"))
        assertEquals("button", role("com.google.android.material.floatingactionbutton.FloatingActionButton"))
        assertEquals("input", role("android.widget.EditText"))
        assertEquals("input", role("android.view.View", node = RawNode(editable = true)))
        assertEquals("switch", role("android.widget.Switch"))
        assertEquals("switch", role("android.widget.ToggleButton"))
        assertEquals("switch", role("com.google.android.material.materialswitch.MaterialSwitch"))
        assertEquals("checkbox", role("android.widget.CheckBox"))
        assertEquals("radio", role("android.widget.RadioButton"))
        assertEquals("image", role("android.widget.ImageView"))
        assertEquals("text", role("android.widget.TextView"))
        assertEquals("heading", role("android.widget.TextView", node = RawNode(heading = true)))
        assertEquals("list", role("androidx.recyclerview.widget.RecyclerView"))
        assertEquals("list", role("android.widget.ListView"))
        assertEquals("list", role("android.widget.HorizontalScrollView"))
        assertEquals("list", role("androidx.viewpager2.widget.ViewPager2"))
        assertEquals("tab", role("androidx.appcompat.app.ActionBar\$Tab"))
        assertEquals("tab", role("com.google.android.material.tabs.TabLayout\$TabView"))
        assertEquals("dropdown", role("android.widget.Spinner"))
        assertEquals("slider", role("android.widget.SeekBar"))
        assertEquals("web", role("android.webkit.WebView"))
        assertEquals("tab", role("android.view.View", roleDescription = "Tab"))
        assertEquals("link", role("android.view.View", roleDescription = "link"))
        assertEquals("switch", role("android.view.View", roleDescription = "Switch"))
        assertEquals("item", role("android.widget.LinearLayout", node = RawNode(clickable = true)))
        assertEquals("checkbox", role("android.view.View", node = RawNode(checkable = true)))
        assertEquals("list", role("android.view.View", node = RawNode(scrollable = true)))
        assertEquals("text", role("android.view.View"))
    }

    @Test
    fun `large label-less surfaces are flagged as unreadable`() {
        val game = RawNode(className = "android.view.SurfaceView", bounds = box(0, 200, 1080, 2000))
        assertTrue(parse(screen(textView("Score 10"), game)).hasUnreadableArea)
        assertFalse(parse(settingsHome()).hasUnreadableArea)
    }

    @Test
    fun `elements from several windows keep their window index`() {
        val popup = RawWindow(SETTINGS, screen(row(textView("Copy")), row(textView("Paste"))))
        val main = RawWindow(SETTINGS, settingsHome())
        val parsed = ScreenParser.parse(listOf(popup, main), 150)

        assertEquals(listOf(0, 0), parsed.elements.take(2).map { it.window })
        assertEquals(1, parsed.elements.last().window)
    }
}
