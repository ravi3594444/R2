package ai.wakey.android.accessibility

// Small builders for Settings-like RawNode trees.

internal const val SETTINGS = "com.android.settings"

internal fun box(left: Int, top: Int, right: Int, bottom: Int) = NodeBounds(left, top, right, bottom)

internal fun textView(text: String, top: Int = 0, heading: Boolean = false) = RawNode(
    className = "android.widget.TextView",
    text = text,
    heading = heading,
    bounds = box(48, top, 1000, top + 60),
)

internal fun row(vararg children: RawNode, top: Int = 0, description: String? = null) = RawNode(
    className = "android.widget.LinearLayout",
    contentDescription = description,
    clickable = true,
    bounds = box(0, top, 1080, top + 160),
    children = children.toList(),
)

internal fun switchWidget(checked: Boolean, clickable: Boolean = false) = RawNode(
    className = "android.widget.Switch",
    checkable = true,
    checked = checked,
    clickable = clickable,
    bounds = box(900, 0, 1040, 80),
)

internal fun screen(vararg children: RawNode) = RawNode(
    className = "android.widget.FrameLayout",
    bounds = box(0, 0, 1080, 2400),
    children = children.toList(),
)

internal fun parse(root: RawNode, maxElements: Int = 150, packageName: String = SETTINGS): ParsedScreen =
    ScreenParser.parse(listOf(RawWindow(packageName, root)), maxElements)

/** Android 13 Settings home: heading, search bar, rows with title + summary. [shift] moves it all as layout jitter would. */
internal fun settingsHome(shift: Int = 0) = screen(
    textView("Settings", top = 120 + shift, heading = true),
    RawNode(
        className = "android.widget.LinearLayout",
        clickable = true,
        viewId = "com.android.settings:id/search_action_bar",
        bounds = box(0, 220 + shift, 1080, 340 + shift),
        children = listOf(textView("Search settings", top = 250 + shift)),
    ),
    RawNode(
        className = "androidx.recyclerview.widget.RecyclerView",
        scrollable = true,
        viewId = "com.android.settings:id/recycler_view",
        bounds = box(0, 400 + shift, 1080, 2400),
        children = listOf(
            row(textView("Network & internet"), textView("Mobile, Wi‑Fi, hotspot"), top = 400 + shift),
            row(textView("Connected devices"), textView("Bluetooth, pairing"), top = 560 + shift),
            row(textView("Apps"), textView("Assistant, recent apps, default apps"), top = 720 + shift),
        ),
    ),
)

/** Bluetooth settings: heading, main switch bar (row + plain Switch), rows, and an icon-only button. */
internal fun bluetoothPage(on: Boolean = false) = screen(
    RawNode(
        className = "android.widget.ImageButton",
        contentDescription = "Navigate up",
        clickable = true,
        bounds = box(0, 100, 140, 240),
    ),
    textView("Bluetooth", top = 260, heading = true),
    row(textView("Use Bluetooth"), switchWidget(checked = on), top = 400),
    row(textView("Pair new device"), top = 560),
    row(textView("Device name"), textView("Pixel 8"), top = 720),
)

internal fun ParsedScreen.lines(): List<String> = elements.map(ScreenFormatter::line)
