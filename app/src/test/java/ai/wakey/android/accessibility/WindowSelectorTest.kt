package ai.wakey.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSelectorTest {
    private val own = "ai.wakey.android"

    private fun window(
        kind: WindowKind,
        layer: Int,
        pkg: String?,
        active: Boolean = false,
        focused: Boolean = active,
    ) = WindowCandidate(kind, layer, active, focused, pkg)

    @Test
    fun `active app plus popups above it, topmost first, without bars, keyboard or overlays`() {
        val windows = listOf(
            window(WindowKind.AccessibilityOverlay, 50, own),
            window(WindowKind.System, 40, "com.android.systemui"),
            window(WindowKind.InputMethod, 30, "com.google.android.inputmethod.latin"),
            window(WindowKind.Application, 21, "com.android.chrome"),
            window(WindowKind.Application, 20, "com.android.chrome", active = true),
            window(WindowKind.Application, 10, "com.google.android.apps.nexuslauncher"),
        )
        val selection = WindowSelector.select(windows, own)

        assertEquals(listOf(3, 4), selection.windows)
        assertTrue(selection.keyboardOpen)
        assertFalse(selection.wakeyActive)
    }

    @Test
    fun `a system dialog or the shade is read when it is the active window`() {
        val windows = listOf(
            window(WindowKind.System, 40, "com.android.systemui", active = true),
            window(WindowKind.Application, 20, "com.android.settings"),
        )
        assertEquals(listOf(0), WindowSelector.select(windows, own).windows)
    }

    @Test
    fun `nothing is read while Wakey itself is in front`() {
        val windows = listOf(window(WindowKind.Application, 20, own, active = true))
        val selection = WindowSelector.select(windows, own)

        assertTrue(selection.windows.isEmpty())
        assertTrue(selection.wakeyActive)
    }

    @Test
    fun `without an active window the focused one, then the topmost app, is used`() {
        val focused = listOf(
            window(WindowKind.Application, 20, "a"),
            window(WindowKind.Application, 10, "b", focused = true),
        )
        assertEquals(listOf(0, 1), WindowSelector.select(focused, own).windows)

        val neither = listOf(
            window(WindowKind.Application, 10, "b"),
            window(WindowKind.Application, 20, "a"),
        )
        assertEquals(listOf(1), WindowSelector.select(neither, own).windows)
    }

    @Test
    fun `no readable windows gives an empty selection`() {
        val selection = WindowSelector.select(listOf(window(WindowKind.InputMethod, 30, "ime")), own)
        assertTrue(selection.windows.isEmpty())
        assertFalse(selection.wakeyActive)
    }
}
