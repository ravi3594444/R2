package ai.wakey.android.ui

import ai.wakey.android.BuildConfig
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.WakeStats
import android.content.Context
import android.os.Build
import java.util.Locale

/** The phone facts the report needs, gathered separately so [diagnosticsReport] stays testable. */
internal data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val appVersion: String,
) {
    companion object {
        fun read(): DeviceFacts =
            DeviceFacts(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)
    }
}

/**
 * A plain-text summary of why "Hey Wakey" may not work on this phone, for the user to copy and send
 * to whoever is helping them. Holds no keys and no audio; only the last misheard phrase, if any.
 */
internal fun diagnosticsReport(
    device: DeviceFacts,
    setup: SetupStatus,
    settings: WakeySettings,
    state: AssistantUiState,
    stats: WakeStats,
    micMuted: Boolean,
    micBoostDb: Int = 0,
): String {
    fun yes(ok: Boolean) = if (ok) "yes" else "NO"
    val mic = when {
        !state.wakeServiceRunning -> "closed"
        micMuted -> "MUTED by Android (records silence)"
        // Kept running for the floating button only: the microphone opens when it is tapped.
        !state.wakeWordEnabled && state.phase != AssistantPhase.Hearing -> "closed until the floating button is tapped"
        else -> "open, level %.2f".format(Locale.US, state.micLevel) + if (micBoostDb > 0) ", boosted +$micBoostDb dB" else ""
    }
    return buildString {
        appendLine("Wakey ${device.appVersion} diagnostics")
        appendLine("Phone: ${device.manufacturer} ${device.model}, Android ${device.androidRelease} (API ${device.sdk})")
        appendLine("Digital assistant app is Wakey: ${yes(setup.defaultAssistant)}")
        appendLine("Background use allowed (battery unrestricted): ${yes(setup.batteryUnrestricted)}")
        appendLine("Microphone permission: ${yes(setup.microphone)}; notifications: ${yes(setup.notifications)}; screen control: ${yes(setup.screenControl)}")
        appendLine(
            "Listen for wake word switch: ${if (settings.wakeListeningWanted) "on" else "off"}; service running: ${yes(state.wakeServiceRunning)}; " +
                "wake word running: ${yes(state.wakeWordEnabled)}",
        )
        appendLine("Floating button: ${if (settings.floatingButton) "on" else "off"}; exact alarms allowed: ${yes(setup.exactAlarms)}")
        appendLine("Microphone now: $mic")
        appendLine("Boost a quiet microphone: ${if (settings.micBoost) "on" else "off"}")
        appendLine("Wake: ${settings.wakeMode.label}, phrase “${settings.wakePhrase}”, sensitivity %.2f, wake sound ${if (settings.wakeSound) "on" else "off"}".format(Locale.US, settings.wakeSensitivity))
        appendLine("Since Wakey started: ${stats.wakes} wakes, ${stats.checksConfirmed} confirmed checks, ${stats.checksRejected} dropped checks, mic muted ${stats.mutedEvents} times")
        stats.lastRejectedHeard?.let { appendLine("Last dropped check heard: “$it”") }
        appendLine("Phase: ${state.phase.label}")
        state.statusMessage?.let { appendLine("Status: $it") }
        state.lastTimings?.let { t ->
            appendLine("Last request: " + timingDetails(t).joinToString("; ") { (k, v) -> "$k $v" })
            if (t.timeline.isNotEmpty()) {
                appendLine("Agent steps (who decided, thinking, doing):")
                t.timeline.forEachIndexed { i, step ->
                    appendLine(
                        "  ${i + 1}. ${step.decidedBy.label} ${formatDuration(step.thinkMs)} → ${step.action}" +
                            (if (step.actMs > 0) " ${formatDuration(step.actMs)}" else "") + if (step.success) "" else " FAILED",
                    )
                }
            }
        }
    }.trimEnd()
}

internal fun diagnosticsReport(
    context: Context,
    settings: WakeySettings,
    state: AssistantUiState,
    stats: WakeStats,
    micMuted: Boolean,
    micBoostDb: Int,
) = diagnosticsReport(DeviceFacts.read(), SetupStatus.read(context), settings, state, stats, micMuted, micBoostDb)
