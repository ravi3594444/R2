package ai.wakey.android.tasks

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

/** When a request should happen, as understood from its words. */
sealed interface TaskRequest {
    /** Do it now; [text] is the request unchanged. */
    data class Now(val text: String) : TaskRequest

    /** Do [text] once the running task finishes ("play this song after this"). */
    data class AfterCurrent(val text: String) : TaskRequest

    /**
     * Do it at [at]. For [TaskKind.Task], [text] is the request without its time words ("call mum");
     * for a reminder, what to remind about; for an alarm, a label (possibly empty). [delay] is set
     * when the time was said as a delay ("in 10 minutes").
     */
    data class Scheduled(val text: String, val kind: TaskKind, val at: ZonedDateTime, val delay: Duration? = null) : TaskRequest

    data object ListTasks : TaskRequest

    /**
     * Cancel every scheduled item ([all]) or the soonest one, limited to [kind] (null: any kind) and
     * to items whose text contains [query], if given. [ringingOnly] ("stop the alarm") only silences a
     * ringing alarm; if none of Wakey's rings, it is another app's, and the request runs as usual.
     */
    data class CancelScheduled(val kind: TaskKind?, val all: Boolean, val query: String?, val ringingOnly: Boolean = false) : TaskRequest
}

/**
 * Turns a request into a [TaskRequest] on the phone, with no model call: "call mum at 4",
 * "ring me at 5", "remind me to drink water in 10 minutes", "play this song after this",
 * "kal subah 7 baje utha dena", "show my tasks".
 *
 * It only claims clear cases. Questions ("what's the weather at 5?") and requests whose trailing
 * time may belong to message or search text ("text mum I'll be home at 6") stay [TaskRequest.Now],
 * so the agent, which can schedule too, reads them whole.
 */
object TaskParser {

    fun parse(utterance: String, now: ZonedDateTime): TaskRequest {
        val text = utterance.trim()
        val unchanged = TaskRequest.Now(text)
        val all = tokenize(text)
        val (from, to) = trimFillers(all.map { it.norm })
        if (from >= to) return unchanged
        val tokens = all.subList(from, to)
        val words = tokens.map { it.norm }
        management(words)?.let { return it }
        // Before the question check: "when you're done, open camera" is not a question.
        afterCurrent(text, tokens, words, now)?.let { return it }
        if (isQuestion(words) || words.any { it in RECURRING }) return unchanged
        return scheduled(text, tokens, words, now) ?: unchanged
    }

    /**
     * Schedules [text] at [whenText], a time on its own as the agent's schedule tool gives it: "at 4 pm",
     * "tomorrow 9:30 am", "16:30", "in 20 minutes", "2026-09-25T09:00". Null if it isn't a future time.
     */
    fun scheduleAt(text: String, whenText: String, kind: TaskKind, now: ZonedDateTime): TaskRequest.Scheduled? {
        val label = text.trim()
        isoDateTime(whenText.trim(), now)?.let { return TaskRequest.Scheduled(label, kind, it) }
        val words = tokenize(whenText).map { it.norm }
        val spec = TimePhrase.parse(words, standalone = true) ?: return null
        val wakeUp = kind == TaskKind.Alarm && label.equals(TaskReplies.WAKE_UP, ignoreCase = true)
        val at = spec.resolve(now, kind, wakeUp) ?: return null
        return TaskRequest.Scheduled(label, kind, at, spec.relative)
    }

    private fun isoDateTime(text: String, now: ZonedDateTime): ZonedDateTime? {
        if (!ISO_DATE_TIME.matches(text)) return null
        val local = try {
            LocalDateTime.parse(text.replace(' ', 'T'))
        } catch (e: DateTimeParseException) {
            return null
        }
        return ZonedDateTime.of(local, now.zone).takeIf { it.isAfter(now) }
    }

    // ---------------------------------------------------------------- list / cancel

    private fun management(words: List<String>): TaskRequest? {
        val phrase = words.joinToString(" ")
        if (LIST_TASKS.any { it.matches(phrase) }) return TaskRequest.ListTasks
        if (STOP_ALARM.matches(phrase)) return TaskRequest.CancelScheduled(TaskKind.Alarm, all = false, query = null, ringingOnly = true)
        val english = CANCEL.matchEntire(phrase)
        val match = english ?: CANCEL_HINGLISH.matchEntire(phrase) ?: return null
        val noun = match.groups["noun"]!!.value
        val kind = when {
            noun.startsWith("reminder") -> TaskKind.Reminder
            noun.startsWith("alarm") || noun.startsWith("timer") -> TaskKind.Alarm
            else -> null
        }
        val all = noun.endsWith("s") || ALL_WORDS.containsMatchIn(phrase)
        val query = english?.groups?.get("query")?.value?.trim()?.ifEmpty { null }
        return TaskRequest.CancelScheduled(kind, all, query)
    }

    private fun isQuestion(words: List<String>): Boolean {
        val first = words.first()
        val second = words.getOrNull(1)
        return when (first) {
            in QUESTION_WORDS -> true
            // "Do I have …" is a question; "do minute baad …" is Hinglish for "in two minutes".
            "do", "does", "did" -> second in PRONOUNS
            // "Can I …", "could it …"; "can you …" was already stripped as politeness.
            in MODALS -> true
            else -> false
        }
    }

    // ---------------------------------------------------------------- "after this"

    private fun afterCurrent(text: String, tokens: List<Token>, words: List<String>, now: ZonedDateTime): TaskRequest? {
        val prefix = AFTER_PREFIXES.firstOrNull { words.startsWith(it) }
            // "Phir se" means "again", not "then".
            ?.takeUnless { it.size == 1 && it[0] in THEN_WORDS && words.getOrNull(1) in AGAIN_WORDS }
        val rest: List<Token> = prefix?.let { tokens.drop(it.size) }
            ?: AFTER_SUFFIXES.firstOrNull { words.endsWith(it) }?.let { tokens.dropLast(it.size) }
            ?: return null
        if (rest.isEmpty()) return null
        val inner = span(text, rest)
        // "After this, remind me at 5 to …" is simply a reminder at 5.
        return when (val parsed = parse(inner, now)) {
            is TaskRequest.Scheduled, is TaskRequest.ListTasks, is TaskRequest.CancelScheduled -> parsed
            else -> TaskRequest.AfterCurrent(inner)
        }
    }

    // ---------------------------------------------------------------- times

    private fun scheduled(text: String, tokens: List<Token>, words: List<String>, now: ZonedDateTime): TaskRequest.Scheduled? {
        // "5 baje alarm laga do", "mujhe kal subah 7 baje yaad dilana ki …".
        hindiMarker(text, tokens, words, now)?.let { return it }
        // "Remind me to … at 5", "wake me up at 6", "set a timer for 10 minutes".
        markerAt(MARKERS, words, 0)?.let { marker ->
            val rest = tokens.drop(marker.length)
            val split = splitTime(rest.map { it.norm }, bareDuration = marker.timer, allowEmpty = true) ?: return null
            return scheduleMarked(text, rest.subList(split.start, split.end), marker, split.spec, now)
        }
        // "Call mum at 4", "at 4, call mum", "tomorrow call mum at 5", "at 6 wake me up".
        val split = splitTime(words) ?: return null
        val middle = tokens.subList(split.start, split.end)
        markerAt(MARKERS, words.subList(split.start, split.end), 0)?.let { marker ->
            return scheduleMarked(text, middle.drop(marker.length), marker, split.spec, now)
        }
        return task(text, middle, split.spec, timeAtEnd = split.end < tokens.size, now)
    }

    private fun hindiMarker(text: String, tokens: List<Token>, words: List<String>, now: ZonedDateTime): TaskRequest.Scheduled? {
        for (i in words.indices) {
            val marker = markerAt(HINDI_MARKERS, words, i) ?: continue
            val before = tokens.subList(0, i).filterNot { it.norm in HINDI_ME }
            val after = tokens.subList(i + marker.length, tokens.size).filterNot { it.norm in HINDI_ME }
            // Usually the time comes first ("5 baje alarm laga do"), sometimes after ("alarm laga do 5 baje").
            splitTime(before.map { it.norm }, bareDuration = marker.timer, allowEmpty = true)?.let { split ->
                return scheduleMarked(text, before.subList(split.start, split.end) + after, marker, split.spec, now)
            }
            val split = splitTime(after.map { it.norm }, bareDuration = marker.timer, allowEmpty = true) ?: return null
            return scheduleMarked(text, before + after.subList(split.start, split.end), marker, split.spec, now)
        }
        return null
    }

    /** Where the time is in [words]: a prefix, a suffix, or both ("tomorrow … at 5"); the rest is [TimeSplit.start] until [TimeSplit.end]. */
    private class TimeSplit(val spec: TimeSpec, val start: Int, val end: Int)

    private fun splitTime(words: List<String>, bareDuration: Boolean = false, allowEmpty: Boolean = false): TimeSplit? {
        val n = words.size
        if (n == 0) return null
        if (allowEmpty) TimePhrase.parse(words, bareDuration)?.let { return TimeSplit(it, n, n) }
        var prefix: TimeSpec? = null
        var prefixEnd = 0
        for (end in n - 1 downTo 1) {
            prefix = TimePhrase.parse(words.subList(0, end), bareDuration) ?: continue
            prefixEnd = end
            break
        }
        var suffix: TimeSpec? = null
        var suffixStart = n
        for (start in prefixEnd + 1 until n) {
            suffix = TimePhrase.parse(words.subList(start, n), bareDuration) ?: continue
            suffixStart = start
            break
        }
        return when {
            prefix != null && suffix != null -> prefix.merge(suffix)?.let { TimeSplit(it, prefixEnd, suffixStart) }
            prefix != null -> TimeSplit(prefix, prefixEnd, n)
            suffix != null -> TimeSplit(suffix, 0, suffixStart)
            else -> null
        }
    }

    private fun scheduleMarked(text: String, labelTokens: List<Token>, marker: Marker, spec: TimeSpec, now: ZonedDateTime): TaskRequest.Scheduled? {
        if (spec.viaFor && marker.kind == TaskKind.Task) return null
        val at = spec.resolve(now, marker.kind, wakeUp = marker.wakeUp) ?: return null
        var label = labelTokens.dropWhile { it.norm in LABEL_CONNECTORS || it.norm in HINDI_ME }
        if (marker.kind == TaskKind.Alarm) label = label.dropWhile { it.norm in ALARM_LABEL_CONNECTORS }
        val labelText = if (label.isEmpty()) "" else span(text, label)
        return TaskRequest.Scheduled(labelText.ifEmpty { marker.defaultLabel }, marker.kind, at, spec.relative)
    }

    private fun task(text: String, taskTokens: List<Token>, spec: TimeSpec, timeAtEnd: Boolean, now: ZonedDateTime): TaskRequest.Scheduled? {
        val trimmed = taskTokens.dropWhile { it.norm in TASK_EDGE_WORDS }.dropLastWhile { it.norm in TASK_EDGE_WORDS }
        if (trimmed.isEmpty() || spec.viaFor) return null
        // The time may belong to what is written or searched: "text mum I'll be home at 6".
        if (timeAtEnd && (trimmed.any { it.norm in CONTENT_WORDS } || text.any { it in QUOTES })) return null
        val at = spec.resolve(now, TaskKind.Task) ?: return null
        val request = span(text, trimmed)
        if (request.length < MIN_TASK_CHARS) return null
        return TaskRequest.Scheduled(request, TaskKind.Task, at, spec.relative)
    }

    private fun markerAt(markers: List<Marker>, words: List<String>, at: Int): Marker? =
        markers.firstOrNull { words.startsWith(it.words, at) }

    // ---------------------------------------------------------------- tokens

    private class Token(val norm: String, val start: Int, val end: Int)

    private val ISO_DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2})?""")
    private val WORD = Regex("""[\p{L}\p{M}\p{N}]+(?:[.:'’][\p{L}\p{M}\p{N}]+)*\.?|@""")
    private val NUMBER_SUFFIX = Regex("""(\d{1,2}(?:[:.]\d{2})?)(am|pm|a\.m|p\.m|h|hr|hrs|m|min|mins|s|sec|secs|baje)""")

    /** Words with their positions in [text]; "4pm" becomes "4" + "pm" so the time grammar sees both. */
    private fun tokenize(text: String): List<Token> = buildList {
        for (match in WORD.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            val norm = normalizeWord(match.value)
            val split = NUMBER_SUFFIX.matchEntire(norm)
            if (split != null && match.value.all { it.code < 128 }) {
                val numberEnd = start + split.groupValues[1].length
                add(Token(split.groupValues[1], start, numberEnd))
                add(Token(split.groupValues[2], numberEnd, end))
            } else {
                add(Token(norm, start, end))
            }
        }
    }

    private fun normalizeWord(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).replace('’', '\'').trimEnd('.')
        val digits = buildString(lower.length) {
            for (c in lower) append(if (c in '०'..'९') '0' + (c - '०') else c)
        }
        return nfc(digits)
    }

    /** The original text from the first to the last of [tokens], keeping inner punctuation; gaps become one space. */
    private fun span(text: String, tokens: List<Token>): String {
        val parts = mutableListOf<String>()
        var runStart = tokens.first().start
        var runEnd = tokens.first().end
        for (token in tokens.drop(1)) {
            val gap = text.substring(runEnd, token.start)
            if (gap.all { it.isWhitespace() || it in ",;:" }) {
                runEnd = token.end
            } else {
                parts += text.substring(runStart, runEnd)
                runStart = token.start
                runEnd = token.end
            }
        }
        parts += text.substring(runStart, runEnd)
        return parts.joinToString(" ").trim().trim(',', ';', ':', '.', '!', '?', '।')
    }

    private fun trimFillers(words: List<String>): Pair<Int, Int> {
        var from = 0
        var to = words.size
        var changed = true
        while (changed && from < to) {
            changed = false
            LEADING_FILLERS.firstOrNull { words.subList(from, to).startsWith(it) }?.let {
                from += it.size
                changed = true
            }
            if (from >= to) break
            val tail = words.subList(from, to)
            TRAILING_FILLERS.firstOrNull { tail.size > it.size && tail.endsWith(it) }?.let {
                to -= it.size
                changed = true
            }
        }
        return from to to
    }

    private fun List<String>.startsWith(prefix: List<String>, at: Int = 0): Boolean =
        at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    private fun List<String>.endsWith(suffix: List<String>): Boolean = startsWith(suffix, size - suffix.size)

    private fun phrases(vararg values: String): List<List<String>> =
        values.map { value -> value.split(' ').map(::nfc) }.sortedByDescending { it.size }

    // ---------------------------------------------------------------- vocabulary

    private class Marker(
        val words: List<String>,
        val kind: TaskKind,
        val defaultLabel: String,
        val wakeUp: Boolean,
        val timer: Boolean,
    ) {
        val length: Int get() = words.size
    }

    private fun markers(kind: TaskKind, label: String, wakeUp: Boolean, timer: Boolean, vararg values: String) =
        phrases(*values).map { Marker(it, kind, label, wakeUp, timer) }

    private val MARKERS: List<Marker> = (
        markers(
            TaskKind.Reminder, "", wakeUp = false, timer = false,
            "remind me", "set a reminder", "set reminder", "set me a reminder", "create a reminder", "add a reminder",
            "make a reminder", "give me a reminder", "put a reminder", "a reminder", "reminder",
        ) + markers(
            TaskKind.Alarm, "Wake up", wakeUp = true, timer = false,
            "wake me up", "wake me", "get me up",
        ) + markers(
            TaskKind.Alarm, "", wakeUp = false, timer = false,
            "ring me", "give me a ring", "set an alarm", "set a alarm", "set alarm", "set the alarm", "set my alarm",
            "set me an alarm", "put an alarm", "create an alarm", "add an alarm", "alarm me", "an alarm", "alarm",
        ) + markers(
            TaskKind.Alarm, "Timer", wakeUp = false, timer = true,
            "set a timer", "set timer", "set the timer", "start a timer", "start timer", "put a timer", "a timer", "timer",
        )
        ).sortedByDescending { it.length }

    private val HINDI_MARKERS: List<Marker> = (
        markers(
            TaskKind.Reminder, "", wakeUp = false, timer = false,
            "yaad dilana", "yaad dila dena", "yaad dila do", "yaad dilao", "yaad dilaana", "yaad karana", "yaad karwana",
            "remind karna", "remind kar dena", "remind karo", "remind kar do", "remind kardo", "remind karana",
            "याद दिलाना", "याद दिला देना", "याद दिलाओ", "याद दिला दो", "याद करवाना", "याद कराना",
        ) + markers(
            TaskKind.Alarm, "Wake up", wakeUp = true, timer = false,
            "utha dena", "utha do", "uthana", "utha dijiye", "jaga dena", "jaga do", "jagana", "jaga dijiye",
            "उठा देना", "उठा दो", "उठाना", "जगा देना", "जगा दो", "जगाना",
        ) + markers(
            TaskKind.Alarm, "", wakeUp = false, timer = false,
            "alarm laga do", "alarm lagao", "alarm laga dena", "alarm lagana", "alarm laga", "alarm set karo",
            "alarm set kar do", "alarm set kardo", "alarm set kar dena", "alarm bajana",
            "अलार्म लगा दो", "अलार्म लगाओ", "अलार्म लगा देना", "अलार्म लगाना", "अलार्म सेट करो",
        ) + markers(
            TaskKind.Alarm, "Timer", wakeUp = false, timer = true,
            "timer laga do", "timer lagao", "timer set karo", "timer set kar do", "टाइमर लगा दो", "टाइमर लगाओ",
        )
        ).sortedByDescending { it.length }

    private val HINDI_ME = nfcSetOf("mujhe", "mujhko", "muje", "hame", "hamein", "humein", "mereko", "मुझे", "मुझको", "हमें")

    /** Words joining a label to the rest: "remind me to …", "… yaad dilana ki …". */
    private val LABEL_CONNECTORS = nfcSetOf("to", "that", "ki", "कि", "की", "please")
    private val ALARM_LABEL_CONNECTORS = nfcSetOf("for", "so", "and", "labelled", "labeled", "called", "named", "ka", "ke", "का", "के")

    /** Stripped from the edges of a scheduled request: "at 4, and then call mum". */
    private val TASK_EDGE_WORDS = nfcSetOf("and", "then", "please", "also", "just", "phir", "aur", "फिर", "और")

    private val CONTENT_WORDS = nfcSetOf(
        "message", "text", "sms", "send", "email", "mail", "reply", "type", "write", "note", "post", "tweet",
        "search", "google", "find", "look", "tell", "say", "saying", "ask", "that", "ki", "bolo", "likho", "bhejo",
        "poocho", "puchho", "batao", "khojo", "dhundo", "लिखो", "भेजो", "बोलो", "बताओ", "खोजो", "ढूंढो", "कि",
    )
    private val QUOTES = setOf('"', '“', '”', '«', '»')
    private const val MIN_TASK_CHARS = 2

    private val RECURRING = nfcSetOf("every", "everyday", "daily", "weekly", "roz", "rozana", "har", "हर", "रोज़", "रोज")
    private val ALL_WORDS = Regex("""\b(?:all|every|each|sab|saare|sare|sabhi)\b""")

    private val QUESTION_WORDS = nfcSetOf(
        "what", "what's", "whats", "when", "when's", "why", "how", "how's", "who", "who's", "whom", "whose", "where",
        "where's", "which", "is", "are", "am", "was", "were", "will", "would", "should", "shall", "has", "have", "had",
        "tell", "kya", "kab", "kaise", "kyun", "kyon", "kaun", "kahan", "kitna", "kitne", "kitni",
        "क्या", "कब", "कैसे", "क्यों", "कौन", "कहाँ", "कहां", "कितना", "कितने", "कितनी",
    )
    private val MODALS = setOf("can", "could", "may", "might")
    private val PRONOUNS = setOf("i", "you", "we", "they", "he", "she", "it", "my", "your", "u")
    private val THEN_WORDS = nfcSetOf("phir", "fir", "फिर")
    private val AGAIN_WORDS = nfcSetOf("se", "से", "sey")

    private val LEADING_FILLERS = phrases(
        "hey wakey", "hi wakey", "ok wakey", "okay wakey", "hello wakey", "wakey", "hey", "hi", "hello", "ok", "okay",
        "please", "pls", "plz", "kindly", "can you please", "could you please", "would you please", "will you please",
        "can you", "could you", "would you", "will you", "can u", "could u", "i want you to", "i need you to", "just",
        "zara", "jara", "कृपया", "ज़रा", "जरा", "प्लीज़", "प्लीज",
    )
    private val TRAILING_FILLERS = phrases(
        "please", "pls", "plz", "thanks", "thank you", "for me", "na", "zara", "ok", "okay", "प्लीज़", "प्लीज", "कृपया", "ना",
    )

    private val AFTER_PREFIXES = phrases(
        "after this", "after that", "after this one", "after this task", "after that task", "after it", "after you finish",
        "after you're done", "after you are done", "after youre done", "after you've finished", "after you have finished",
        "after finishing this", "when you're done", "when you are done", "when youre done", "when done", "when finished",
        "when you finish", "when you're finished", "when you are finished", "once you're done", "once you are done",
        "once youre done", "once done", "once you finish", "once that's done", "once this is done", "and then", "then",
        "iske baad", "is ke baad", "uske baad", "us ke baad", "iske bad", "uske bad", "phir", "fir",
        "इसके बाद", "इस के बाद", "उसके बाद", "उस के बाद", "फिर",
    )
    private val AFTER_SUFFIXES = phrases(
        "after this", "after that", "after this one", "after this task", "after that task", "after you finish",
        "after you're done", "after you are done", "after youre done", "after you've finished", "after you have finished",
        "when you're done", "when you are done", "when youre done", "when done", "when finished", "when you finish",
        "when you're finished", "when you are finished", "once you're done", "once you are done", "once youre done",
        "once done", "once you finish", "iske baad", "is ke baad", "uske baad", "us ke baad", "iske bad", "uske bad",
        "इसके बाद", "इस के बाद", "उसके बाद", "उस के बाद",
    )

    private val LIST_TASKS = listOf(
        Regex(
            "(?:(?:show|list|see|check|read|tell)(?: me)?(?: all)?(?: of)?(?: my| the)?|what(?:'s| is| are)?(?: all)?(?: my| the)?|" +
                "whats(?: my| the)?|my|any|do i have any|are there any|i want to see my) ?" +
                "(?:scheduled |upcoming |pending |queued |planned )?(?:tasks?|reminders?|alarms?|timers?|schedule|task list|to ?do list|to-?dos?)" +
                "(?: (?:do i have|have i got|are there|are scheduled|scheduled|pending|for today|for tomorrow|today|tomorrow))?",
        ),
        Regex("(?:what(?:'s| is)|whats) (?:scheduled|planned|queued|coming up|up next|next|on my schedule|on my list)"),
        Regex("what (?:do i have|have i got|have i) (?:scheduled|planned|queued|lined up|coming up)"),
        Regex("(?:mere |meri |sab |saare |sare )?(?:tasks?|reminders?|alarms?|schedule) (?:dikhao|batao|dikha do|bata do|dikhaiye|bataiye)"),
        Regex("(?:kya kya |kya )?(?:scheduled|schedule|plan) (?:hai|he)"),
    )
    private val CANCEL = Regex(
        "(?:cancel|clear|delete|remove|dismiss|drop|forget|turn off)(?: all(?: of)?| every| each)?(?: my| the| these| those)?" +
            "(?: scheduled| upcoming| pending| queued| next)? (?<noun>tasks?|reminders?|alarms?|timers?)" +
            "(?: (?:to|for|about|called|named|that says) (?<query>.+))?",
    )
    /** "Stop the alarm" silences a ringing alarm; "stop" alone is Stop. */
    private val STOP_ALARM = Regex("(?:stop|silence|mute|quiet)(?: the| my| that| this)? (?:alarms?|timers?|ringing)|stop ringing")
    private val CANCEL_HINGLISH = Regex(
        "(?:(?:sab|saare|sare|sabhi|mere|meri|mera) )*(?<noun>tasks?|reminders?|alarms?|timers?) " +
            "(?:cancel|delete|clear|hatao|hata do|hata dijiye|band)(?: karo| kar do| kardo| kijiye| kar dijiye)?",
    )
}
