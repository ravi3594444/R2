package ai.wakey.android.accessibility

/** The accessibility window types Wakey distinguishes. */
enum class WindowKind { Application, InputMethod, System, AccessibilityOverlay, Other }

/** What is known about one on-screen window before reading its tree. */
data class WindowCandidate(
    val kind: WindowKind,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val packageName: String?,
)

/** Which windows to read, and what else the agent should know about the rest. */
data class WindowSelection(
    /** Indices into the candidates to read, topmost first; the last is the main window. */
    val windows: List<Int>,
    val keyboardOpen: Boolean,
    /** Wakey's own UI is the active window, so nothing is read. */
    val wakeyActive: Boolean,
)

/**
 * Picks the windows worth showing the agent: the active window (an app, a system dialog or the
 * notification shade) plus app popups above it such as menus and autocomplete lists. Status and
 * navigation bars, the keyboard's keys and accessibility overlays (including Wakey's pill) are
 * skipped; an open keyboard is only reported.
 */
object WindowSelector {
    /** [ownPackage] is Wakey's; its windows are never read. */
    fun select(windows: List<WindowCandidate>, ownPackage: String): WindowSelection {
        val keyboardOpen = windows.any { it.kind == WindowKind.InputMethod }
        val readable = windows.indices.filter { windows[it].kind == WindowKind.Application || windows[it].kind == WindowKind.System }
        val main = readable.firstOrNull { windows[it].active }
            ?: readable.firstOrNull { windows[it].focused }
            ?: readable.filter { windows[it].kind == WindowKind.Application }.maxByOrNull { windows[it].layer }
            ?: return WindowSelection(emptyList(), keyboardOpen, wakeyActive = false)
        if (windows[main].packageName == ownPackage) return WindowSelection(emptyList(), keyboardOpen, wakeyActive = true)
        val popups = readable
            .filter {
                val w = windows[it]
                it != main && w.kind == WindowKind.Application && w.layer > windows[main].layer && w.packageName != ownPackage
            }
            .sortedByDescending { windows[it].layer }
        return WindowSelection(popups + main, keyboardOpen, wakeyActive = false)
    }
}
