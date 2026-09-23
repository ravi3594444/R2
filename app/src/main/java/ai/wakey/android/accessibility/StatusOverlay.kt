package ai.wakey.android.accessibility

import ai.wakey.android.R
import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Small pill near the top of the screen showing what Wakey is doing, with a Stop button.
 *
 * It is an accessibility overlay, so it needs no draw-over-apps permission, and it never takes
 * focus, so the app underneath stays usable. Public methods may be called from any thread; all view
 * work runs on the main thread.
 */
internal class StatusOverlay(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var pill: View? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var onStop: (() -> Unit)? = null
    private var hiders = 0

    /** Where the pill is on screen, readable from any thread; null while not shown. */
    @Volatile private var shownBounds: NodeBounds? = null

    /** Shows the pill, or updates its text and Stop action if already shown. */
    fun show(text: String, onStop: () -> Unit) {
        main.post {
            this.onStop = onStop
            if (pill == null) attach()
            label?.text = service.getString(R.string.overlay_status, text)
        }
    }

    fun hide() {
        main.post(::detach)
    }

    /** Removes the pill immediately when called on the main thread (service teardown). */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) detach() else main.post(::detach)
    }

    /** True if the pill currently covers any part of [area], so a gesture there would hit it. */
    fun covers(area: NodeBounds): Boolean {
        val b = shownBounds ?: return false
        return area.left < b.right && b.left < area.right && area.top < b.bottom && b.top < area.bottom
    }

    /**
     * Runs [block] with the pill invisible and untouchable, e.g. for a screenshot or a gesture that
     * lands on it. Waits a few frames first so the change reaches the screen.
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val wasShown = withContext(NonCancellable + Dispatchers.Main.immediate) {
            hiders++
            applyHidden()
        }
        try {
            if (wasShown) delay(HIDE_SETTLE_MS)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                hiders--
                applyHidden()
            }
        }
    }

    private fun attach() {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val accent = service.getColor(R.color.wakey_accent)

        val dot = View(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
        }
        val text = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (service.resources.displayMetrics.widthPixels * 0.6f).roundToInt()
        }
        val stop = Button(service).apply {
            setText(R.string.overlay_stop)
            contentDescription = service.getString(R.string.overlay_stop_description)
            isAllCaps = false
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(STOP_BACKGROUND)
            }
            setOnClickListener { this@StatusOverlay.onStop?.invoke() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) }
        }
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(PILL_BACKGROUND)
            }
            addView(dot)
            addView(text)
            addView(stop)
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val location = IntArray(2).also(view::getLocationOnScreen)
                shownBounds = NodeBounds(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
            title = WINDOW_TITLE
        }
        try {
            windowManager.addView(root, layout)
        } catch (_: RuntimeException) {
            // The service is disconnecting; there is nothing to show the pill over.
            return
        }
        pill = root
        label = text
        params = layout
        applyHidden()
    }

    private fun detach() {
        pill?.let { view -> runCatching { windowManager.removeView(view) } }
        pill = null
        label = null
        params = null
        onStop = null
        shownBounds = null
    }

    /** Applies the current [hiders] count; returns true if the pill exists. */
    private fun applyHidden(): Boolean {
        val view = pill ?: return false
        val layout = params ?: return false
        val hidden = hiders > 0
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        layout.flags = if (hidden) {
            layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { windowManager.updateViewLayout(view, layout) }
        return true
    }

    private companion object {
        const val HIDE_SETTLE_MS = 120L
        const val WINDOW_TITLE = "Wakey status"
        const val PILL_BACKGROUND = 0xEB1C1F26.toInt()
        const val STOP_BACKGROUND = 0x33FFFFFF
    }
}
