package ai.wakey.android.accessibility

import java.security.MessageDigest

/** Why the screen can't be read normally, beyond what the tree itself shows. */
enum class ScreenNotice { Locked, WakeyInForeground }

/** Builds the [ScreenObservation] the agent reads: one compact line per element. */
object ScreenFormatter {

    /**
     * [windows] and [parsed] come from the same read, topmost window first; the last window is the
     * main one.
     */
    fun observation(
        packageName: String?,
        appLabel: String?,
        windows: List<RawWindow>,
        parsed: ParsedScreen,
        keyboardOpen: Boolean,
        notice: ScreenNotice? = null,
    ): ScreenObservation {
        val warning = warning(parsed, notice)
        return ScreenObservation(
            packageName = packageName,
            appLabel = appLabel,
            elements = parsed.elements.map { it.element },
            truncated = parsed.truncated,
            signature = signature(packageName, parsed.elements),
            text = render(packageName, appLabel, windows, parsed, keyboardOpen, warning),
            warning = warning,
        )
    }

    /** One element, e.g. `[4] item "Connected devices" – "Bluetooth, pairing" (tap)`. */
    fun line(e: ParsedElement): String = buildString {
        val el = e.element
        append('[').append(el.id).append("] ").append(el.role)
        e.labels.forEachIndexed { i, label -> append(if (i == 0) " " else " – ").append(quoted(label)) }
        if (el.editable && el.text != null) append(" value=").append(quoted(el.text))
        if (el.hint != null) append(" hint=").append(quoted(el.hint))
        if (e.labels.isEmpty() && el.hint == null) {
            el.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
            e.context?.let { append(" near ").append(quoted(it)) }
        }
        if (e.password) append(" (password)")
        el.checked?.let { checked ->
            val (on, off) = if (el.role == "switch") " (on)" to " (off)" else " (checked)" to " (unchecked)"
            append(if (checked) on else off)
        }
        if (e.selected) append(" (selected)")
        if (!el.enabled) append(" (disabled)")
        if (el.editable && el.focused) append(" (focused)")
        val actions = listOfNotNull(
            "tap".takeIf { el.clickable && !el.editable },
            "type".takeIf { el.editable },
            "scroll".takeIf { el.scrollable },
        )
        if (actions.isNotEmpty()) append(" (").append(actions.joinToString(", ")).append(')')
    }

    /**
     * Stable hash of what is on screen: package plus each element's role, labels, value and state.
     * Bounds and ids are left out so layout jitter doesn't look like a new screen.
     */
    fun signature(packageName: String?, elements: List<ParsedElement>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: String?) {
            digest.update(value.orEmpty().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        put(packageName)
        for (e in elements) {
            val el = e.element
            put(el.role)
            e.labels.forEach(::put)
            put(if (el.editable) el.text else null)
            put(el.hint)
            put("${el.checked}|${e.selected}|${el.enabled}")
            digest.update(1)
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Why the agent may need a screenshot or the user's help, or null when the tree looks complete. */
    fun warning(parsed: ParsedScreen, notice: ScreenNotice?): String? = when {
        notice == ScreenNotice.Locked ->
            "The phone is locked. Wakey can't unlock it; ask the user to unlock the phone first."
        notice == ScreenNotice.WakeyInForeground ->
            "Wakey itself is on screen. Open the app you need first."
        parsed.elements.isEmpty() ->
            "No readable controls on this screen. It may be protected, still loading, or drawn as graphics; " +
                "take a screenshot to see it."
        parsed.hasUnreadableArea ->
            "Part of this screen is drawn as graphics and can't be read. Take a screenshot if what you need isn't listed."
        else -> null
    }

    private fun render(
        packageName: String?,
        appLabel: String?,
        windows: List<RawWindow>,
        parsed: ParsedScreen,
        keyboardOpen: Boolean,
        warning: String?,
    ): String = buildString {
        append("App: ")
        val labelled = appLabel != null && packageName != null && appLabel != packageName
        append(if (labelled) "$appLabel ($packageName)" else packageName ?: appLabel ?: "unknown")
        if (keyboardOpen) append("\nKeyboard is open.")
        val multiWindow = parsed.elements.map { it.window }.distinct().size > 1
        var window = -1
        for (e in parsed.elements) {
            if (multiWindow && e.window != window) {
                window = e.window
                val main = window == windows.lastIndex
                append("\n-- ").append(if (main) "Main window" else "Popup (${windows[window].packageName ?: "unknown"})").append(" --")
            }
            append('\n').append(line(e))
        }
        if (parsed.truncated) append("\n(More elements not shown; scroll or target a specific label.)")
        if (warning != null) append("\nNote: ").append(warning)
    }

    /** Double quotes would break the line format, so they become single quotes. */
    private fun quoted(text: String) = "\"" + text.replace('"', '\'') + "\""
}
