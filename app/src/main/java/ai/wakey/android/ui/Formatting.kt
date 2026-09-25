package ai.wakey.android.ui

import ai.wakey.android.core.TurnTimings
import ai.wakey.android.tasks.TaskStatus
import ai.wakey.android.tasks.TaskTime
import ai.wakey.android.tasks.WakeyTask
import ai.wakey.android.tts.VoiceOption
import java.time.ZonedDateTime

/** "180 ms", "1.2 s", "14 s". Locale-independent so it reads the same on every phone. */
internal fun formatDuration(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    if (value < 1_000) return "$value ms"
    val tenths = (value + 50) / 100
    return if (tenths < 100) "${tenths / 10}.${tenths % 10} s" else "${(value + 500) / 1_000} s"
}

/** "850", "1.4k", "2k", "14k". */
internal fun formatCount(count: Int): String {
    val value = count.coerceAtLeast(0)
    if (value < 1_000) return value.toString()
    val tenths = (value + 50) / 100
    return when {
        tenths >= 100 -> "${(value + 500) / 1_000}k"
        tenths % 10 == 0 -> "${tenths / 10}k"
        else -> "${tenths / 10}.${tenths % 10}k"
    }
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

/**
 * The compact row under a Wakey reply, e.g.
 * "wake 180 ms · STT connect 420 ms · transcript 310 ms · first action 1.2 s · voice 2.1 s · 3 steps · 2 LLM calls · 1.4k tokens".
 * Only measured fields appear, so typed requests and direct commands stay short.
 */
internal fun formatTimingSummary(t: TurnTimings): String = buildList {
    t.wakeDetectionMs?.let { add("wake ${formatDuration(it)}") }
    t.sttConnectMs?.let { add("STT connect ${formatDuration(it)}") }
    t.transcriptionMs?.let { add("transcript ${formatDuration(it)}") }
    t.headStartMs?.let { add("head start ${formatDuration(it)}") }
    t.firstActionMs?.let { add("first action ${formatDuration(it)}") }
    t.spokenReplyMs?.let { add("voice ${formatDuration(it)}") }
    if (t.steps > 0) add(plural(t.steps, "step", "steps"))
    if (t.decisionCalls > 0) add(plural(t.decisionCalls, "Jev decision", "Jev decisions"))
    if (t.llmCalls > 0) add(plural(t.llmCalls, "LLM call", "LLM calls"))
    val tokens = t.promptTokens + t.completionTokens
    if (tokens > 0) add("${formatCount(tokens)} ${if (tokens == 1) "token" else "tokens"}")
}.joinToString(" · ")

/** Label/value rows for Settings → Diagnostics. Includes every field, with "—" when unmeasured. */
internal fun timingDetails(t: TurnTimings): List<Pair<String, String>> {
    fun ms(value: Long?) = value?.let(::formatDuration) ?: "—"
    return listOf(
        "Input" to t.source.label,
        "Route" to t.route.ifBlank { "—" },
        "Wake detection" to ms(t.wakeDetectionMs),
        "STT connect" to ms(t.sttConnectMs),
        "Final transcript" to ms(t.transcriptionMs),
        "Speech session" to ms(t.speechSessionMs),
        "Agent head start" to ms(t.headStartMs),
        "First action" to ms(t.firstActionMs),
        "Reply audio starts" to ms(t.spokenReplyMs),
        "Total" to ms(t.totalMs),
        "Agent steps" to t.steps.toString(),
        "Jev decisions" to t.decisionCalls.toString(),
        "LLM calls" to t.llmCalls.toString(),
        "Tokens (prompt + completion)" to "${t.promptTokens} + ${t.completionTokens}",
    )
}

/** "1.0×". */
internal fun formatSpeechRate(rate: Float): String {
    val tenths = Math.round(rate * 10)
    return "${tenths / 10}.${tenths % 10}×"
}

/** Indian English voices first (Meena, Priya, Naveen…), otherwise keeping the engine's order. */
internal fun indianEnglishFirst(voices: List<VoiceOption>): List<VoiceOption> =
    voices.sortedBy { if (it.languageTag.equals("en-IN", ignoreCase = true)) 0 else 1 }

/**
 * Connection tests return a plain summary; the controller prefixes failures with "Failed:" and
 * reports a missing test as "Not available".
 */
internal fun isFailureSummary(summary: String): Boolean =
    summary.startsWith("Failed", ignoreCase = true) || summary == "Not available"

/** Mirrors how settings store a wake phrase, so "changed?" compares like with like. */
internal fun normalizeWakePhrase(phrase: String): String = phrase.trim().replace(Regex("\\s+"), " ")

/**
 * The second line of a task in a list: when it runs ("4:00 PM · in 12 min", "Tomorrow 9:00 AM"),
 * where it stands ("Up next", "Waiting for you to unlock") or how it ended.
 */
internal fun taskStatusLine(task: WakeyTask, now: ZonedDateTime): String {
    val due = task.dueAtMs?.let { TaskTime.at(it, now.zone) }
    return when (task.status) {
        TaskStatus.Scheduled -> due?.let { at ->
            // A countdown helps for the next few hours; beyond that the time says enough.
            val soon = at.isBefore(now.plusHours(COUNTDOWN_HOURS))
            listOfNotNull(TaskTime.label(at, now), TaskTime.countdown(at, now)?.takeIf { soon }).joinToString(" · ")
        } ?: "Scheduled"
        TaskStatus.Queued -> if (due != null) "Due now · up next" else "After the current task"
        TaskStatus.WaitingForUnlock -> "Due ${due?.let { TaskTime.clockPadded(it) } ?: "now"} · unlock to run"
        TaskStatus.Running -> "Running now"
        TaskStatus.Done, TaskStatus.Failed, TaskStatus.Missed, TaskStatus.Cancelled -> {
            val ended = (task.finishedAtMs ?: task.createdAtMs).let { TaskTime.label(TaskTime.at(it, now.zone), now) }
            val outcome = task.result?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: task.status.label
            "$ended · $outcome"
        }
    }
}

private const val COUNTDOWN_HOURS = 3L
