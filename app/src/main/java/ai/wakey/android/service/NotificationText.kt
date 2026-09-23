package ai.wakey.android.service

import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.Speaker

/**
 * What the listening notification shows. [publicTitle] is the lock-screen version, so it never
 * contains speech, replies or the names of apps being operated.
 */
internal data class ListeningContent(val title: String, val publicTitle: String, val text: String?)

internal const val MAX_TITLE_CHARS = 100
internal const val MAX_TEXT_CHARS = 400

/** Formats [state] for the listening notification. */
internal fun listeningContent(state: AssistantUiState, wakePhrase: String): ListeningContent {
    val publicTitle = when (state.phase) {
        // Idle only shows for the moment between entering the foreground and the mic starting.
        AssistantPhase.Idle, AssistantPhase.WakeListening ->
            wakePhrase.trim().takeIf { it.isNotEmpty() }?.let { "Listening for “$it”" } ?: "Listening for the wake word"
        AssistantPhase.Hearing -> "Hearing you…"
        AssistantPhase.Thinking -> "Thinking…"
        AssistantPhase.Acting -> "Acting…"
        AssistantPhase.Speaking -> "Speaking…"
    }
    val action = state.currentAction?.description?.trim().orEmpty()
    val title = if (state.phase == AssistantPhase.Acting && action.isNotEmpty()) "Acting: $action" else publicTitle
    return ListeningContent(
        title = clip(title, MAX_TITLE_CHARS),
        publicTitle = publicTitle,
        text = listeningText(state)?.let { clip(it, MAX_TEXT_CHARS) },
    )
}

/** Live transcript, else a pending question, else what the current phase is doing, else the status line. */
private fun listeningText(state: AssistantUiState): String? {
    val action = state.currentAction
    val transcript = state.liveTranscript.takeIf { state.phase == AssistantPhase.Hearing }
    val detail = when (state.phase) {
        AssistantPhase.Thinking ->
            action?.result ?: action?.description ?: state.entries.lastOrNull { it.speaker == Speaker.User }?.text
        AssistantPhase.Acting ->
            action?.result ?: action?.takeIf { it.maxSteps > 1 }?.let { "Step ${it.step} of ${it.maxSteps}" }
        AssistantPhase.Speaking -> state.entries.lastOrNull()?.takeIf { it.speaker == Speaker.Wakey }?.text
        AssistantPhase.Idle, AssistantPhase.WakeListening, AssistantPhase.Hearing -> null
    }
    return listOf(transcript, state.pendingConfirmation?.question, detail, state.statusMessage)
        .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
}

/** Caps [text] at [max] characters (ellipsis included) without splitting a surrogate pair. */
internal fun clip(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max - 1
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end).trimEnd() + "…"
}
