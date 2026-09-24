package ai.wakey.android.tasks

import ai.wakey.android.agent.AgentPrompt.ReplyLanguage
import java.time.ZonedDateTime

/** Short spoken replies for scheduling, listing and cancelling tasks, in Hindi when the request was. */
object TaskReplies {

    /** "Okay, I'll call mum at 4 PM." / "Alarm set. I'll ring tomorrow at 6 AM." */
    fun scheduled(request: TaskRequest.Scheduled, now: ZonedDateTime, heard: String): String {
        if (ReplyLanguage.of(heard) == ReplyLanguage.Hindi) return scheduledHindi(request, now)
        val whenText = TaskTime.spoken(request.at, now, request.delay)
        val text = request.text.trim()
        return when (request.kind) {
            TaskKind.Task -> "Okay, I'll ${sentenceCase(secondPerson(text))} $whenText."
            TaskKind.Reminder -> when {
                text.isEmpty() -> "Okay, I'll remind you $whenText."
                // Romanised Hindi reads badly after "remind you to": quote it instead.
                ReplyLanguage.of(text) != ReplyLanguage.English -> "Okay, I'll remind you $whenText: $text."
                text.substringBefore(' ').lowercase() in ABOUT_WORDS -> "Okay, I'll remind you ${secondPerson(text)} $whenText."
                else -> "Okay, I'll remind you to ${sentenceCase(secondPerson(text))} $whenText."
            }
            TaskKind.Alarm -> when (text) {
                WAKE_UP -> "Okay, I'll wake you up $whenText."
                TIMER -> "Timer set. I'll ring $whenText."
                "" -> "Alarm set. I'll ring $whenText."
                else -> "Alarm set for ${secondPerson(text)}. I'll ring $whenText."
            }
        }
    }

    /** "Okay, after this I'll play this song." */
    fun queued(text: String): String =
        if (ReplyLanguage.of(text) == ReplyLanguage.Hindi) "ठीक है, इसके बाद यह करूँगा: $text।"
        else "Okay, after this I'll ${sentenceCase(secondPerson(text))}."

    /** A spoken summary of what is running, next and scheduled, at most [MAX_LISTED] items. */
    fun list(board: TaskBoard, now: ZonedDateTime): String {
        val parts = mutableListOf<String>()
        board.running?.let { parts += "Right now I'm working on: ${secondPerson(it.title)}." }
        if (board.upNext.isNotEmpty()) parts += "Next: ${board.upNext.joinToString(", then ") { secondPerson(it.title) }}."
        if (board.waiting.isNotEmpty()) {
            parts += "Waiting for you to unlock the phone: ${board.waiting.joinToString(", ") { secondPerson(it.title) }}."
        }
        val scheduled = board.scheduled
        if (scheduled.isEmpty()) {
            if (parts.isEmpty()) return "You have nothing scheduled."
            parts += "Nothing else is scheduled."
        } else {
            val shown = scheduled.take(MAX_LISTED).map { describe(it, now) }
            val more = scheduled.size - shown.size
            val count = if (scheduled.size == 1) "one thing" else "${scheduled.size} things"
            parts += "You have $count scheduled: ${joinAnd(shown)}${if (more > 0) ", and $more more" else ""}."
        }
        return parts.joinToString(" ")
    }

    /** "Cancelled 2 scheduled reminders." / "There's nothing scheduled to cancel." */
    fun cancelled(cancelled: List<WakeyTask>, request: TaskRequest.CancelScheduled, now: ZonedDateTime): String {
        val noun = when (request.kind) {
            TaskKind.Reminder -> "reminder"
            TaskKind.Alarm -> "alarm"
            else -> "task"
        }
        return when {
            cancelled.isEmpty() && request.query != null -> "I couldn't find a scheduled $noun about ${request.query}."
            cancelled.isEmpty() -> "There's no scheduled $noun to cancel."
            cancelled.size == 1 -> "Cancelled ${describe(cancelled.single(), now)}."
            else -> "Cancelled ${cancelled.size} scheduled ${noun}s."
        }
    }

    /** "call mum at 4 PM", "an alarm tomorrow at 6 AM", "a reminder to drink water at 5 PM". */
    fun describe(task: WakeyTask, now: ZonedDateTime): String {
        val whenText = task.dueAtMs?.let { " " + TaskTime.spoken(TaskTime.at(it, now.zone), now) }.orEmpty()
        val text = secondPerson(task.text.trim())
        return when (task.kind) {
            TaskKind.Task -> "$text$whenText"
            TaskKind.Reminder -> if (text.isEmpty()) "a reminder$whenText" else "a reminder to $text$whenText"
            TaskKind.Alarm -> when (task.text) {
                TIMER -> "a timer$whenText"
                "", WAKE_UP -> "an alarm$whenText"
                else -> "an alarm for $text$whenText"
            }
        }
    }

    /** What a reminder's notification or speech says when it fires. */
    fun reminderDue(task: WakeyTask): String =
        if (task.text.isBlank()) "Here's your reminder." else "Reminder: ${secondPerson(task.text.trim())}."

    /** Spoken when a task's time comes while the phone is locked. */
    fun unlockToRun(task: WakeyTask): String = "It's time to ${sentenceCase(secondPerson(task.title))}. Unlock your phone and I'll do it."

    /** Rewrites the user's words for Wakey to say back: "call my mum" → "call your mum". */
    fun secondPerson(text: String): String = PRONOUN.replace(text) { match ->
        val word = match.value
        val swapped = PRONOUNS.getValue(word.lowercase())
        if (word.first().isUpperCase() && word.lowercase() != "i") swapped.replaceFirstChar { it.uppercaseChar() } else swapped
    }

    /** Lower-cases a leading ordinary word so it reads mid-sentence; keeps names like "YouTube" and "WhatsApp". */
    internal fun sentenceCase(text: String): String {
        val first = text.substringBefore(' ')
        val ordinary = first.length > 1 && first.first().isUpperCase() && first.drop(1).all { !it.isUpperCase() }
        return if (ordinary && first.lowercase() !in KEEP_CAPITALISED) text.replaceFirstChar { it.lowercaseChar() } else text
    }

    private fun scheduledHindi(request: TaskRequest.Scheduled, now: ZonedDateTime): String {
        val whenText = TaskTime.spokenHindi(request.at, now, request.delay)
        val text = request.text.trim()
        return when (request.kind) {
            TaskKind.Task -> "ठीक है, $whenText यह करूँगा: $text।"
            TaskKind.Reminder -> if (text.isEmpty()) "ठीक है, $whenText याद दिलाऊँगा।" else "ठीक है, $whenText याद दिलाऊँगा: $text।"
            TaskKind.Alarm -> "ठीक है, $whenText अलार्म बजेगा।"
        }
    }

    private fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    const val WAKE_UP = "Wake up"
    const val TIMER = "Timer"
    private const val MAX_LISTED = 3
    private val ABOUT_WORDS = setOf("about", "of", "that")
    private val KEEP_CAPITALISED = setOf("i", "i'm", "i'll")
    private val PRONOUNS = mapOf(
        "my" to "your", "me" to "you", "mine" to "yours", "myself" to "yourself", "i" to "you", "i'm" to "you're",
        "i'll" to "you'll", "i've" to "you've", "i'd" to "you'd",
    )
    private val PRONOUN = Regex("""(?i)(?<![\p{L}'])(?:my|me|mine|myself|i|i'm|i'll|i've|i'd)(?![\p{L}'])""")
}
