package ai.wakey.android.assist

import ai.wakey.android.R
import ai.wakey.android.WakeyApp
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.Speaker
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shown if the user invokes the assistant by habit (e.g. holding the power button) while Wakey is
 * the digital assistant — a role Wakey holds for background listening, not for this gesture. It
 * simply listens like "Hey Wakey" would, shows progress and hides when the request is finished.
 */
class WakeySession(context: Context) : VoiceInteractionSession(context) {
    private var scope: CoroutineScope? = null
    private lateinit var title: TextView
    private lateinit var detail: TextView

    override fun onCreateContentView(): View {
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt() }
        title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        detail = TextView(context).apply {
            setTextColor(Color.rgb(0xC9, 0xD2, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, dp(10))
        }
        val stop = Button(context).apply {
            text = context.getString(R.string.assist_stop)
            setOnClickListener {
                WakeyApp.graph.controller.stop()
                hide()
            }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x17, 0x1B, 0x24))
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            addView(title)
            addView(detail)
            addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        return FrameLayout(context).apply {
            setBackgroundColor(Color.argb(0x55, 0, 0, 0))
            // Tapping outside the card dismisses it; the request carries on.
            setOnClickListener { hide() }
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(24))
            })
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val controller = WakeyApp.graph.controller
        // The panel is visible, so this is a moment Android lets Wakey renew background listening.
        controller.restoreWakeListening(context)
        controller.onAssistInvoked()
        scope?.cancel()
        scope = MainScope().also { session ->
            session.launch {
                var started = false
                controller.state.collectLatest { state ->
                    render(state)
                    if (state.isWorking()) {
                        started = true
                    } else {
                        // Linger briefly on the reply or problem, then get out of the way.
                        delay(if (started) FINISHED_LINGER_MS else FAILED_LINGER_MS)
                        hide()
                    }
                }
            }
        }
    }

    override fun onHide() {
        scope?.cancel()
        scope = null
        super.onHide()
    }

    private fun render(state: AssistantUiState) {
        if (!::title.isInitialized) return
        title.text = state.phase.label
        detail.text = when {
            state.pendingConfirmation != null -> state.pendingConfirmation.question
            state.liveTranscript.isNotBlank() -> state.liveTranscript
            state.currentAction != null -> state.currentAction.description
            state.statusIsError && state.statusMessage != null -> state.statusMessage
            else -> state.entries.lastOrNull { it.speaker == Speaker.Wakey }?.text.orEmpty()
        }
    }

    private fun AssistantUiState.isWorking(): Boolean = pendingConfirmation != null ||
        phase == AssistantPhase.Hearing || phase == AssistantPhase.Thinking ||
        phase == AssistantPhase.Acting || phase == AssistantPhase.Speaking

    private companion object {
        const val FINISHED_LINGER_MS = 1_500L
        const val FAILED_LINGER_MS = 3_000L
    }
}
