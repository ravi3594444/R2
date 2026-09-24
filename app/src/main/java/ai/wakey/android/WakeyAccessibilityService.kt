package ai.wakey.android

import ai.wakey.android.accessibility.ElementMatcher
import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.NodeBounds
import ai.wakey.android.accessibility.NodeReader
import ai.wakey.android.accessibility.ParsedElement
import ai.wakey.android.accessibility.RawWindow
import ai.wakey.android.accessibility.ScreenCapture
import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.accessibility.ScreenFormatter
import ai.wakey.android.accessibility.ScreenNotice
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScreenParser
import ai.wakey.android.accessibility.ScreenshotEncoder
import ai.wakey.android.accessibility.ScrollAction
import ai.wakey.android.accessibility.ScrollCandidate
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.accessibility.ScrollPlan
import ai.wakey.android.accessibility.ScrollPlanner
import ai.wakey.android.accessibility.SettleTracker
import ai.wakey.android.accessibility.StatusOverlay
import ai.wakey.android.accessibility.captureScreen
import ai.wakey.android.accessibility.findFirst
import ai.wakey.android.accessibility.screenBounds
import ai.wakey.android.accessibility.selfOrAncestor
import ai.wakey.android.agent.ActionOutcome
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Wakey's eyes and hands: reads other apps' UI through accessibility and taps, types, scrolls,
 * goes back or home and takes screenshots for the agent.
 *
 * Tree reads and node actions run on a background thread (they are binder calls into the other
 * app); gestures and the status pill run on the main thread. It refuses to tap, type or scroll on
 * the lock screen, and never logs screen contents or typed text.
 */
class WakeyAccessibilityService : AccessibilityService(), ScreenController {
    private val settle = SettleTracker(SystemClock::uptimeMillis)
    private val overlayHolder = lazy { StatusOverlay(this) }
    private val overlay by overlayHolder
    private val appLabels = ConcurrentHashMap<String, String>()

    /** The latest [observe] result; element ids refer to it. */
    @Volatile private var snapshot: ScreenCapture? = null
    @Volatile private var lastForeground: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val source = event.packageName?.toString()
        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (source == packageName) {
            // Wakey's activities are a real foreground app; the status pill's updates are not screen changes.
            if (stateChanged && event.className?.startsWith(packageName) == true) lastForeground = source
            return
        }
        settle.markChanged()
        if (stateChanged && source != null && source != SYSTEM_UI_PACKAGE && source != currentImePackage()) {
            lastForeground = source
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    override val foregroundPackage: String?
        get() = lastForeground ?: rootInActiveWindow?.packageName?.toString()

    override val isLocked: Boolean
        get() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override suspend fun observe(maxElements: Int): ScreenObservation = withContext(Dispatchers.IO) {
        val capture = capture(maxElements)
        snapshot = capture
        val notice = when {
            isLocked -> ScreenNotice.Locked
            capture.wakeyActive -> ScreenNotice.WakeyInForeground
            else -> null
        }
        val pkg = capture.packageName
        ScreenFormatter.observation(pkg, pkg?.let(::appLabel), capture.windows, capture.parsed, capture.keyboardOpen, notice)
    }

    override suspend fun tap(target: ElementTarget): ActionOutcome = withContext(Dispatchers.IO) {
        lockedOutcome() ?: when (val found = resolve(target)) {
            is Resolution.Missing -> ActionOutcome(false, found.message)
            is Resolution.Found -> tapNode(found.element, found.node)
        }
    }

    override suspend fun enterText(target: ElementTarget?, text: String, submit: Boolean): ActionOutcome =
        withContext(Dispatchers.IO) {
            lockedOutcome()?.let { return@withContext it }
            val field = if (target != null) {
                when (val found = resolve(target)) {
                    is Resolution.Missing -> return@withContext ActionOutcome(false, found.message)
                    is Resolution.Found -> found.node.findFirst(EDITABLE_SEARCH_LIMIT) { it.isEditable } ?: openField(found)
                }
            } else {
                focusedInput() ?: firstEditable()
            }
            if (field == null) ActionOutcome(false, "There's no text field on screen.") else typeInto(field, text, submit)
        }

    override suspend fun scroll(direction: ScrollDirection, target: ElementTarget?): ActionOutcome =
        withContext(Dispatchers.IO) {
            lockedOutcome()?.let { return@withContext it }
            val nodes: List<AccessibilityNodeInfo>
            val plan: ScrollPlan
            var screenArea: NodeBounds? = null
            if (target != null) {
                val found = when (val resolved = resolve(target)) {
                    is Resolution.Missing -> return@withContext ActionOutcome(false, resolved.message)
                    is Resolution.Found -> resolved
                }
                val scrollable = found.node.selfOrAncestor { it.isScrollable }
                    ?: found.node.findFirst(SCROLLABLE_SEARCH_LIMIT) { it.isScrollable }
                nodes = listOfNotNull(scrollable)
                plan = if (scrollable == null) {
                    ScrollPlan.Swipe(found.node.screenBounds())
                } else {
                    ScrollPlanner.plan(direction, listOf(scrollCandidate(0, scrollable)), explicit = true)
                }
            } else {
                val capture = capture()
                nodes = capture.parsed.elements.filter { it.element.scrollable }.mapNotNull(capture::node)
                plan = ScrollPlanner.plan(direction, nodes.mapIndexed(::scrollCandidate))
                screenArea = capture.mainBounds
            }
            val word = direction.name.lowercase()
            when (plan) {
                is ScrollPlan.AtEnd -> ActionOutcome(false, "Can't scroll $word any further.")
                is ScrollPlan.Perform -> {
                    val node = nodes[plan.candidate.key]
                    if (plan.actions.any { node.performAction(it.androidId()) }) {
                        ActionOutcome(true, "Scrolled $word")
                    } else {
                        swipe(direction, plan.candidate.bounds)
                    }
                }
                is ScrollPlan.Swipe -> swipe(direction, plan.bounds ?: screenArea ?: displayBounds())
            }
        }

    override fun back(): ActionOutcome = globalAction(GLOBAL_ACTION_BACK, "Went back", "Couldn't go back.")

    override fun home(): ActionOutcome =
        globalAction(GLOBAL_ACTION_HOME, "Went to the home screen", "Couldn't go to the home screen.")

    // Return type inferred: inside this class the simple name ScreenshotResult means the platform's class.
    override suspend fun screenshot() = when (val shot = takeShotWithRetry()) {
        is Shot.Failed -> ScreenshotEncoder.failure(shot.code)
        is Shot.Taken -> try {
            withContext(Dispatchers.Default) { ScreenshotEncoder.encode(shot.buffer, shot.colorSpace) }
        } finally {
            shot.buffer.close()
        }
    }

    override suspend fun awaitSettled(timeoutMs: Long) = settle.awaitSettled(timeoutMs)

    /** Starts [intent] as a new task. Returns false if no app handles it or the start is refused. */
    override fun launch(intent: Intent): Boolean = try {
        startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: RuntimeException) {
        false
    }

    override fun showStatus(text: String, onStop: () -> Unit) = overlay.show(text, onStop)

    override fun hideStatus() {
        if (overlayHolder.isInitialized()) overlay.hide()
    }

    private sealed interface Resolution {
        class Found(val element: ParsedElement, val node: AccessibilityNodeInfo) : Resolution
        class Missing(val message: String) : Resolution
    }

    private sealed interface Shot {
        class Taken(val buffer: HardwareBuffer, val colorSpace: ColorSpace?) : Shot
        class Failed(val code: Int) : Shot
    }

    private suspend fun capture(maxElements: Int = LOOKUP_MAX_ELEMENTS): ScreenCapture {
        val job = currentCoroutineContext().job
        return captureScreen(maxElements) { job.ensureActive() }
    }

    /**
     * By id from the latest snapshot (refreshed, and re-found by label if the view now shows
     * something else), else by label on a fresh read. An id whose element doesn't match the given
     * label is treated as a mistake and the label wins.
     */
    private suspend fun resolve(target: ElementTarget): Resolution {
        val label = target.label?.trim()?.takeIf { it.isNotEmpty() }
        val id = target.id
        if (id != null) {
            val snap = snapshot
            val element = snap?.element(id)
            if (element != null && (label == null || ElementMatcher.matches(element, label))) {
                val node = snap?.node(element)
                if (node != null && isStillSame(node, element)) return Resolution.Found(element, node)
                val again = label ?: element.labels.firstOrNull()
                    ?: return Resolution.Missing("Element $id changed since the screen was read. Read the screen again.")
                return resolveLabel(again)
            }
            if (label == null) return Resolution.Missing(unknownId(id, snap))
        }
        return if (label != null) resolveLabel(label) else Resolution.Missing("No element was given to act on.")
    }

    private suspend fun resolveLabel(label: String): Resolution {
        val capture = capture()
        val elements = capture.parsed.elements
        val match = ElementMatcher.find(elements, label)
        val node = match?.let(capture::node)
        if (match != null && node != null) return Resolution.Found(match, node)
        val close = ElementMatcher.suggestions(elements, label)
        return Resolution.Missing(
            if (close.isEmpty()) "Couldn't find “$label”; nothing readable is on screen."
            else "Couldn't find “$label” on screen. Close matches: ${close.quotedList()}."
        )
    }

    private fun unknownId(id: Int, snap: ScreenCapture?): String {
        val elements = snap?.parsed?.elements.orEmpty()
        if (elements.isEmpty()) return "There's no element $id. Read the screen first."
        val sample = ElementMatcher.suggestions(elements, "")
        val onScreen = if (sample.isEmpty()) "" else " On screen: ${sample.quotedList()}."
        return "There's no element $id on the latest screen (ids 1–${elements.size}).$onScreen"
    }

    /** Recycled list rows keep their node but show new content, so compare the primary label too. */
    private fun isStillSame(node: AccessibilityNodeInfo, element: ParsedElement): Boolean {
        if (!node.refresh()) return false
        val expected = element.labels.firstOrNull() ?: return node.isVisibleToUser
        val raw = NodeReader(maxNodes = RECHECK_MAX_NODES, maxDepth = RECHECK_MAX_DEPTH).read(node)
        val current = ScreenParser.parse(listOf(RawWindow(null, raw)), RECHECK_MAX_NODES).elements.firstOrNull()
        return current?.labels?.firstOrNull().equals(expected, ignoreCase = true)
    }

    private suspend fun tapNode(element: ParsedElement, node: AccessibilityNodeInfo): ActionOutcome {
        val name = nameOf(element)
        if (!node.isEnabled) return ActionOutcome(false, "${name.capitalised()} is disabled right now.")
        val clickable = node.selfOrAncestor { it.isClickable }
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return ActionOutcome(true, "Tapped $name")
        val bounds = node.screenBounds()
        return if (!bounds.isEmpty && tapAt(bounds.centerX, bounds.centerY)) {
            ActionOutcome(true, "Tapped $name")
        } else {
            ActionOutcome(false, "Couldn't tap $name.")
        }
    }

    /** A search icon or a fake search box usually opens the real field when tapped. */
    private suspend fun openField(found: Resolution.Found): AccessibilityNodeInfo? {
        if (!tapNode(found.element, found.node).success) return null
        // Return as soon as the real field takes focus instead of waiting out the opening animation.
        repeat((FIELD_OPEN_TIMEOUT_MS / FIELD_POLL_MS).toInt()) {
            delay(FIELD_POLL_MS)
            focusedInput()?.let { return it }
        }
        return firstEditable()
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && it.packageName?.toString() != packageName }

    private suspend fun firstEditable(): AccessibilityNodeInfo? {
        val capture = capture()
        return capture.parsed.elements.firstOrNull { it.element.editable && it.element.enabled }?.let(capture::node)
    }

    private suspend fun typeInto(field: AccessibilityNodeInfo, text: String, submit: Boolean): ActionOutcome {
        val typed = when {
            text.isEmpty() -> "Cleared the field"
            field.isPassword -> "Filled in the password field"
            else -> "Typed “${shorten(text)}”"
        }
        // Focus first: IME enter only works on the focused field.
        if (!field.isFocused && !field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionOutcome(false, "Couldn't type into that field.")
        }
        if (!submit) return ActionOutcome(true, typed)
        return if (pressEnter(field)) {
            ActionOutcome(true, "$typed and pressed enter")
        } else {
            ActionOutcome(false, "$typed but couldn't submit it. Tap the search or send button instead.")
        }
    }

    private suspend fun pressEnter(field: AccessibilityNodeInfo): Boolean {
        field.refresh()
        if (field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) return true
        // Fields without IME enter still react to the keyboard's own action key; show the keyboard if needed.
        val key = imeActionKey() ?: run {
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle.awaitSettled(KEYBOARD_TIMEOUT_MS)
            imeActionKey()
        } ?: return false
        if (key.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = key.screenBounds()
        return !bounds.isEmpty && tapAt(bounds.centerX, bounds.centerY)
    }

    private fun imeActionKey(): AccessibilityNodeInfo? {
        val keyboard = windows.orEmpty().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root
            ?: return null
        return keyboard.findFirst(IME_SEARCH_LIMIT) { ElementMatcher.isImeActionKey((it.contentDescription ?: it.text)?.toString()) }
    }

    private fun scrollCandidate(index: Int, node: AccessibilityNodeInfo): ScrollCandidate {
        val offered = node.actionList.mapTo(HashSet()) { it.id }
        val className = node.className?.toString().orEmpty()
        return ScrollCandidate(
            key = index,
            bounds = node.screenBounds(),
            actions = ScrollAction.entries.filterTo(HashSet()) { it.androidId() in offered },
            horizontalHint = className.endsWith("HorizontalScrollView") || className.contains("ViewPager"),
        )
    }

    private suspend fun swipe(direction: ScrollDirection, area: NodeBounds): ActionOutcome {
        val word = direction.name.lowercase()
        val line = ScrollPlanner.swipe(direction, area)
        val path = Path().apply {
            moveTo(line.startX.toFloat(), line.startY.toFloat())
            lineTo(line.endX.toFloat(), line.endY.toFloat())
        }
        val span = NodeBounds(
            minOf(line.startX, line.endX), minOf(line.startY, line.endY),
            maxOf(line.startX, line.endX) + 1, maxOf(line.startY, line.endY) + 1,
        )
        return if (gesture(GestureDescription.StrokeDescription(path, 0, SWIPE_MS), span)) {
            ActionOutcome(true, "Scrolled $word")
        } else {
            ActionOutcome(false, "Couldn't scroll $word.")
        }
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(GestureDescription.StrokeDescription(path, 0, TAP_MS), NodeBounds(x, y, x + 1, y + 1))
    }

    /** Dispatches a one-stroke gesture on the main thread, lifting the status pill if it's in the way. */
    private suspend fun gesture(stroke: GestureDescription.StrokeDescription, area: NodeBounds): Boolean {
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val run: suspend () -> Boolean = { withContext(Dispatchers.Main) { dispatch(description) } }
        return if (overlayHolder.isInitialized() && overlay.covers(area)) overlay.hiddenWhile(run) else run()
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = cont.resume(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = cont.resume(false)
        }
        if (!dispatchGesture(gesture, callback, null)) cont.resume(false)
    }

    /** Captures with the status pill hidden; if the previous capture was too recent, waits and retries once. */
    private suspend fun takeShotWithRetry(): Shot {
        val first = overlay.hiddenWhile { takeShot() }
        if (first !is Shot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        delay(SCREENSHOT_RETRY_MS)
        return overlay.hiddenWhile { takeShot() }
    }

    private suspend fun takeShot(): Shot = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                cont.resume(Shot.Taken(buffer, result.colorSpace)) { _, _, _ -> buffer.close() }
            }

            override fun onFailure(errorCode: Int) = cont.resume(Shot.Failed(errorCode))
        })
    }

    private fun globalAction(action: Int, done: String, failed: String): ActionOutcome =
        if (performGlobalAction(action)) ActionOutcome(true, done) else ActionOutcome(false, failed)

    private fun lockedOutcome(): ActionOutcome? =
        if (isLocked) ActionOutcome(false, "The phone is locked. Unlock it first, then ask again.") else null

    private fun appLabel(pkg: String): String? = appLabels[pkg] ?: try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0)
        }
        packageManager.getApplicationLabel(info).toString().also { appLabels[pkg] = it }
    } catch (_: PackageManager.NameNotFoundException) {
        // Not visible to Wakey under package-visibility rules; the package name still identifies it.
        null
    }

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)
            ?.packageName

    private fun displayBounds(): NodeBounds =
        resources.displayMetrics.let { NodeBounds(0, 0, it.widthPixels, it.heightPixels) }

    private fun release() {
        if (instance === this) instance = null
        if (overlayHolder.isInitialized()) overlay.dismiss()
        snapshot = null
    }

    private fun ScrollAction.androidId(): Int = when (this) {
        ScrollAction.Down -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        ScrollAction.Up -> AccessibilityAction.ACTION_SCROLL_UP.id
        ScrollAction.Left -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        ScrollAction.Right -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        ScrollAction.Forward -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ScrollAction.Backward -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }

    private fun nameOf(element: ParsedElement): String =
        element.displayLabel?.let { "“$it”" } ?: "element ${element.id} (${element.element.role})"

    private fun String.capitalised() = replaceFirstChar { it.uppercaseChar() }

    private fun List<String>.quotedList() = joinToString(", ") { "“$it”" }

    private fun shorten(text: String): String {
        val line = text.replace('\n', ' ')
        return if (line.length <= SHORT_TEXT_CHARS) line else line.take(SHORT_TEXT_CHARS - 1) + "…"
    }

    companion object {
        @Volatile var instance: WakeyAccessibilityService? = null
            private set

        /** The live screen controller, or null when the service is not enabled/bound. */
        val controller: ScreenController? get() = instance

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** Label lookups read more than an observation shows, so off-list elements can still be found. */
        private const val LOOKUP_MAX_ELEMENTS = 1_000
        private const val RECHECK_MAX_NODES = 64
        private const val RECHECK_MAX_DEPTH = 8
        private const val EDITABLE_SEARCH_LIMIT = 64
        private const val SCROLLABLE_SEARCH_LIMIT = 100
        private const val IME_SEARCH_LIMIT = 300
        private const val TAP_MS = 50L
        private const val SWIPE_MS = 350L
        private const val SCREENSHOT_RETRY_MS = 1_000L
        private const val FIELD_OPEN_TIMEOUT_MS = 1_500L
        private const val FIELD_POLL_MS = 100L
        private const val KEYBOARD_TIMEOUT_MS = 1_000L
        private const val SHORT_TEXT_CHARS = 40
    }
}
