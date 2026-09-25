package ai.wakey.android.tasks

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Times for speech and task lists. Locale-independent, like the rest of Wakey's formatting. */
object TaskTime {

    fun at(epochMs: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone)

    /** "4 PM", "4:30 PM". */
    fun clock(t: ZonedDateTime): String {
        val hour = t.hour % 12
        val suffix = if (t.hour < 12) "AM" else "PM"
        val h = if (hour == 0) 12 else hour
        return if (t.minute == 0) "$h $suffix" else "$h:${t.minute.toString().padStart(2, '0')} $suffix"
    }

    /** "4:00 PM": fixed width for lists. */
    fun clockPadded(t: ZonedDateTime): String {
        val hour = t.hour % 12
        return "${if (hour == 0) 12 else hour}:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) "AM" else "PM"}"
    }

    /**
     * When, as said in a reply: "at 4 PM", "tomorrow at 9:30 AM", "on Friday at 5 PM",
     * "on 3 October at 5 PM", or "in 10 minutes" for a short [delay].
     */
    fun spoken(at: ZonedDateTime, now: ZonedDateTime, delay: Duration? = null): String {
        if (delay != null && delay < Duration.ofHours(DELAY_WORDS_MAX_HOURS)) return "in ${duration(delay)}"
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val clock = clock(at)
        return when {
            days == 0L -> "at $clock"
            days == 1L -> "tomorrow at $clock"
            days in 2..6 -> "on ${weekday(at)} at $clock"
            else -> "on ${at.dayOfMonth} ${month(at)} at $clock"
        }
    }

    /** Hindi: "शाम 4 बजे", "कल सुबह 9:30 बजे", "10 मिनट में". */
    fun spokenHindi(at: ZonedDateTime, now: ZonedDateTime, delay: Duration? = null): String {
        if (delay != null && delay < Duration.ofHours(DELAY_WORDS_MAX_HOURS)) {
            val minutes = (delay.seconds + 30) / 60
            return when {
                delay.seconds < 60 -> "${delay.seconds} सेकंड में"
                minutes < 60 -> "$minutes मिनट में"
                minutes % 60 == 0L -> "${minutes / 60} घंटे में"
                else -> "${minutes / 60} घंटे ${minutes % 60} मिनट में"
            }
        }
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> ""
            1L -> "कल "
            2L -> "परसों "
            else -> "${at.dayOfMonth} ${month(at)} को "
        }
        val period = when (at.hour) {
            in 4..11 -> "सुबह"
            in 12..15 -> "दोपहर"
            in 16..19 -> "शाम"
            else -> "रात"
        }
        val hour = at.hour % 12
        val h = if (hour == 0) 12 else hour
        val clock = if (at.minute == 0) "$h" else "$h:${at.minute.toString().padStart(2, '0')}"
        return "$day$period $clock बजे"
    }

    /** Compact label for lists: "4:00 PM", "Tomorrow 9:30 AM", "Fri 5:00 PM", "3 Oct 5:00 PM". */
    fun label(at: ZonedDateTime, now: ZonedDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val clock = clockPadded(at)
        return when {
            days == 0L -> clock
            days == 1L -> "Tomorrow $clock"
            days == -1L -> "Yesterday $clock"
            days in 2..6 -> "${at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $clock"
            else -> "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $clock"
        }
    }

    /** "in 45 s", "in 12 min", "in 2 h 5 min"; null once due or more than a day away. */
    fun countdown(at: ZonedDateTime, now: ZonedDateTime): String? {
        val seconds = Duration.between(now, at).seconds
        if (seconds <= 0 || seconds > 24 * 3600) return null
        if (seconds < 60) return "in $seconds s"
        val minutes = (seconds + 59) / 60
        return if (minutes < 60) "in $minutes min" else if (minutes % 60 == 0L) "in ${minutes / 60} h" else "in ${minutes / 60} h ${minutes % 60} min"
    }

    /** "45 seconds", "1 minute", "10 minutes", "2 hours", "1 hour 30 minutes". */
    fun duration(d: Duration): String {
        val seconds = d.seconds
        if (seconds < 60) return plural(seconds, "second")
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return plural(minutes, "minute")
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) plural(hours, "hour") else "${plural(hours, "hour")} ${plural(rest, "minute")}"
    }

    private fun plural(count: Long, unit: String) = "$count $unit${if (count == 1L) "" else "s"}"

    private fun weekday(t: ZonedDateTime) = t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun month(t: ZonedDateTime) = t.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** Delays longer than this are said as a clock time ("at 7:15 PM") rather than "in 5 hours 12 minutes". */
    private const val DELAY_WORDS_MAX_HOURS = 3L
}
