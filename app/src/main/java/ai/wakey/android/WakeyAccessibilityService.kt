package ai.wakey.android

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Android's UI tree is the initial perception and action layer for a later agent loop. */
class WakeyAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false
    private var lastExternalPackage: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePackage = event?.packageName?.toString() ?: return
        if (sourcePackage == packageName) return
        lastExternalPackage = sourcePackage
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed({
            refreshScheduled = false
            val root = rootInActiveWindow ?: return@postDelayed
            if (root.packageName?.toString() == lastExternalPackage) {
                lastExternalScreen = describe(root)
            }
        }, 250)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** A bounded summary helps avoid passing huge or stale UI trees to an agent. */
    private fun describe(root: AccessibilityNodeInfo): String {
        val lines = mutableListOf("App: ${root.packageName}")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 100) {
            val node = queue.removeFirst()
            val label = (node.text ?: node.contentDescription)?.toString()?.take(100)
            if (!label.isNullOrBlank()) {
                val action = if (node.isClickable) " [tap]" else if (node.isEditable) " [type]" else ""
                lines.add("${label.replace('\n', ' ')}$action")
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return lines.joinToString("\n")
    }

    /** Selects an accessible label, preferring a clickable ancestor. */
    fun tapLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 200) {
            val node = queue.removeFirst()
            val matches = listOfNotNull(node.text, node.contentDescription)
                .any { it.toString().equals(label, ignoreCase = true) }
            if (matches) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 200) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        @Volatile var instance: WakeyAccessibilityService? = null
            private set
        @Volatile var lastExternalScreen: String = "No other app has been inspected yet."
            private set
    }
}
