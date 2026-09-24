package ai.wakey.android.tasks

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Canonical form for comparing Devanagari words (keyboard and STT spell nukta letters differently). */
internal fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

internal fun nfcSetOf(vararg values: String): Set<String> = values.mapTo(HashSet(), ::nfc)

internal enum class Meridiem { AM, PM }

internal enum class DayPeriod { Morning, Afternoon, Evening, Night }

/** A time phrase as spoken, before it is placed on the calendar. */
internal data class TimeSpec(
    val relative: Duration? = null,
    val hour: Int? = null,
    val minute: Int = 0,
    val meridiem: Meridiem? = null,
    /** 24-hour form ("16:30", "07:00", noon, midnight): no AM/PM guessing. */
    val clock24: Boolean = false,
    val dayOffset: Int? = null,
    val weekday: DayOfWeek? = null,
    /** "next Friday": not today even if today is Friday. */
    val nextWeek: Boolean = false,
    val period: DayPeriod? = null,
    /** Said as "for 7" or "for 10 minutes", which only alarms, timers and reminders use as a time. */
    val viaFor: Boolean = false,
) {
    /**
     * The moment this phrase means, or null when it names no usable time (a task needs a clock time or
     * a delay; a reminder may default to the morning, afternoon, evening or night).
     *
     * A bare hour like "at 4" is the next 4:00 AM or PM, except that 1–5 means PM for tasks and
     * reminders (nobody schedules a call for 4 AM by accident) and [wakeUp] alarms are always AM.
     */
    fun resolve(now: ZonedDateTime, kind: TaskKind, wakeUp: Boolean = false): ZonedDateTime? {
        relative?.let { return now.plus(it).truncatedTo(ChronoUnit.SECONDS) }
        val times = clockCandidates(kind, wakeUp) ?: defaultTime(kind)?.let(::listOf) ?: return null
        val today = now.toLocalDate()
        val dates: List<LocalDate> = when {
            dayOffset != null -> listOf(today.plusDays(dayOffset.toLong()))
            weekday != null -> {
                val first = today.with(if (nextWeek) TemporalAdjusters.next(weekday) else TemporalAdjusters.nextOrSame(weekday))
                listOf(first, first.plusWeeks(1))
            }
            else -> listOf(today, today.plusDays(1))
        }
        val candidates = dates.flatMap { date -> times.map { ZonedDateTime.of(date, it, now.zone) } }
        candidates.filter { it.isAfter(now) }.minOrNull()?.let { return it }
        // "Today at 9" said at 10, or "tonight at 12" (which is past midnight): the next day at that time.
        return if (dayOffset != null) candidates.minOrNull()?.plusDays(1)?.takeIf { it.isAfter(now) } else null
    }

    /** Combines a phrase said before a request with one said after it ("tomorrow … at 5"); null if they clash. */
    fun merge(other: TimeSpec): TimeSpec? {
        if (relative != null || other.relative != null) return null
        if (hour != null && other.hour != null) return null
        if (dayOffset != null && other.dayOffset != null && dayOffset != other.dayOffset) return null
        if (weekday != null && other.weekday != null) return null
        if (period != null && other.period != null && period != other.period) return null
        val day = dayOffset ?: other.dayOffset
        val weekday = weekday ?: other.weekday
        if (day != null && weekday != null) return null
        val clock = if (hour != null) this else other
        return TimeSpec(
            hour = clock.hour,
            minute = clock.minute,
            meridiem = clock.meridiem,
            clock24 = clock.clock24,
            dayOffset = day,
            weekday = weekday,
            nextWeek = nextWeek || other.nextWeek,
            period = period ?: other.period,
            viaFor = viaFor || other.viaFor,
        )
    }

    private fun clockCandidates(kind: TaskKind, wakeUp: Boolean): List<LocalTime>? {
        val h = hour ?: return null
        fun at(hour24: Int) = LocalTime.of(hour24, minute)
        return when {
            clock24 -> listOf(at(h))
            meridiem == Meridiem.AM -> listOf(at(h % 12))
            meridiem == Meridiem.PM -> listOf(at(h % 12 + 12))
            period != null -> listOf(at(period.hour24(h)))
            h == 12 -> listOf(at(12))
            wakeUp -> listOf(at(h))
            kind != TaskKind.Alarm && h in 1..5 -> listOf(at(h + 12))
            else -> listOf(at(h), at(h + 12))
        }
    }

    /** Reminders without a clock time: "remind me tomorrow", "remind me this evening". */
    private fun defaultTime(kind: TaskKind): LocalTime? {
        if (kind != TaskKind.Reminder || (period == null && dayOffset == null && weekday == null)) return null
        return when (period) {
            DayPeriod.Morning, null -> LocalTime.of(9, 0)
            DayPeriod.Afternoon -> LocalTime.of(14, 0)
            DayPeriod.Evening -> LocalTime.of(18, 0)
            DayPeriod.Night -> LocalTime.of(20, 0)
        }
    }

    private fun DayPeriod.hour24(h: Int): Int = when (this) {
        DayPeriod.Morning -> h % 12
        DayPeriod.Afternoon, DayPeriod.Evening -> if (h == 12) 12 else h % 12 + 12
        DayPeriod.Night -> when (h) {
            12 -> 0
            in 1..5 -> h
            else -> h % 12 + 12
        }
    }
}

/**
 * Grammar for time phrases over normalised (lower-case) words. [parse] succeeds only when every
 * word belongs to the phrase, so callers can try prefixes and suffixes of a request to find where
 * its time is. Covers English ("at 4:30 pm", "tomorrow at 9", "in 10 minutes", "on Friday
 * evening"), Hinglish ("kal subah 7 baje", "saade 4 baje", "10 minute baad") and Devanagari.
 */
internal object TimePhrase {

    /**
     * [bareDuration] also accepts a duration with no "in"/"after" ("timer 5 minutes"); [standalone]
     * is for text known to be a time ("16:30", "20 minutes"), so bare numbers and durations count.
     */
    fun parse(words: List<String>, bareDuration: Boolean = false, standalone: Boolean = false): TimeSpec? {
        if (words.isEmpty()) return null
        val builder = Builder(bareDuration || standalone, standalone)
        var i = 0
        while (i < words.size) i = builder.segment(words, i) ?: return null
        return builder.build()
    }

    private class Builder(private val bareDuration: Boolean, private val bareClock: Boolean) {
        var relative: Duration? = null
        var hour: Int? = null
        var minute = 0
        var meridiem: Meridiem? = null
        var clock24 = false
        var anchored = false
        var viaFor = false
        var dayOffset: Int? = null
        var weekday: DayOfWeek? = null
        var nextWeek = false
        var period: DayPeriod? = null

        /** Consumes one segment at [i]; returns the index after it, or null if nothing fits. */
        fun segment(w: List<String>, i: Int): Int? {
            if (w[i] in FILLERS) return i + 1
            return relative(w, i) ?: day(w, i) ?: period(w, i) ?: clock(w, i)
        }

        fun build(): TimeSpec? {
            val calendar = dayOffset != null || weekday != null || period != null
            val spec = TimeSpec(relative, hour, minute, meridiem, clock24, dayOffset, weekday, nextWeek, period, viaFor)
            val delay = relative
            if (delay != null) {
                return spec.takeIf { hour == null && !calendar && delay >= MIN_DELAY && delay <= MAX_DELAY }
            }
            if (dayOffset != null && weekday != null) return null
            if (hour == null) return spec.takeIf { calendar && !viaFor }
            // A bare number is only a time next to a day or a part of the day: "call 4" is not "at 4".
            return spec.takeIf { anchored || calendar || bareClock }
        }

        private fun relative(w: List<String>, i: Int): Int? {
            if (relative != null) return null
            var j = i
            var introduced = false
            var byFor = false
            when (w[j]) {
                "in", "after", "within" -> introduced = true
                "for" -> byFor = true
            }
            if (introduced || byFor) j++
            var total = Duration.ZERO
            var lastUnit: Duration? = null
            while (true) {
                val part = durationPart(w, j) ?: break
                total = total.plus(part.amount)
                lastUnit = part.unit
                j = part.next
                if (w.getOrNull(j) == "and" && durationPart(w, j + 1) != null) j++
            }
            if (lastUnit == null) return null
            if (w.matches(j, "and", "a", "half")) {
                total = total.plus(lastUnit.dividedBy(2))
                j += 3
            }
            var closed = false
            when {
                w.matches(j, "from", "now") -> { j += 2; closed = true }
                w.matches(j, "ke", "baad") || w.matches(j, "के", "बाद") -> { j += 2; closed = true }
                w.getOrNull(j) in DELAY_ENDINGS -> { j++; closed = true }
            }
            if (!introduced && !byFor && !closed && !bareDuration) return null
            relative = total
            if (byFor && !closed) viaFor = true
            return j
        }

        private fun day(w: List<String>, i: Int): Int? {
            DAY_WORDS.firstOrNull { w.matches(i, it.first) }?.let { (phrase, offset) ->
                if (dayOffset != null) return null
                dayOffset = offset
                return i + phrase.size
            }
            var j = i
            var next = false
            if (w[j] == "on") j++
            when (w.getOrNull(j)) {
                "next", "coming" -> { next = true; j++ }
                "this" -> j++
            }
            val day = w.getOrNull(j)?.let { WEEKDAYS[it] } ?: return null
            if (weekday != null) return null
            weekday = day
            nextWeek = next
            return j + 1
        }

        private fun period(w: List<String>, i: Int): Int? {
            val match = PERIOD_WORDS.firstOrNull { w.matches(i, it.phrase) } ?: return null
            if (period != null) return null
            if (match.today) {
                if (dayOffset != null && dayOffset != 0) return null
                dayOffset = 0
            }
            period = match.period
            return i + match.phrase.size
        }

        private fun clock(w: List<String>, i: Int): Int? {
            if (hour != null) return null
            var j = i
            when (w[j]) {
                "at", "@", "around", "about", "by", "till", "until" -> { anchored = true; j++ }
                "for" -> { anchored = true; viaFor = true; j++ }
            }
            val word = w.getOrNull(j) ?: return null
            NAMED_TIMES[word]?.let { (h, m) ->
                set(h, m, twentyFour = true)
                anchored = true
                return j + 1
            }
            if (w.matches(j, "12", "noon")) {
                set(12, 0, twentyFour = true)
                anchored = true
                return j + 2
            }
            var offset = 0
            when {
                word in HALF_PAST_HINDI -> { offset = 30; j++ }
                word in QUARTER_PAST_HINDI -> { offset = 15; j++ }
                word in QUARTER_TO_HINDI -> { offset = -15; j++ }
                w.matches(j, "half", "past") -> { offset = 30; j += 2 }
                w.matches(j, "quarter", "past") -> { offset = 15; j += 2 }
                w.matches(j, "quarter", "to") -> { offset = -15; j += 2 }
                word in ONE_THIRTY -> return fixed(w, j, 1)
                word in TWO_THIRTY -> return fixed(w, j, 2)
            }
            if (offset != 0) anchored = true
            val token = w.getOrNull(j) ?: return null
            val parsed = clockNumber(token) ?: HOUR_WORDS[token]?.let { ClockNumber(it, 0, false, word = true) } ?: return null
            // "4:30" is plainly a time; "4" or "4.30" could be anything.
            if (':' in token) anchored = true
            j++
            when {
                w.getOrNull(j) in AM_WORDS -> { meridiem = Meridiem.AM; j++ }
                w.getOrNull(j) in PM_WORDS -> { meridiem = Meridiem.PM; j++ }
                w.matches(j, "a", "m") -> { meridiem = Meridiem.AM; j += 2 }
                w.matches(j, "p", "m") -> { meridiem = Meridiem.PM; j += 2 }
            }
            when {
                w.getOrNull(j) in OCLOCK -> { anchored = true; j++ }
                w.matches(j, "o", "clock") -> { anchored = true; j += 2 }
            }
            if (meridiem != null) anchored = true
            // Spelled-out hours only count with "at", AM/PM or o'clock: "call one" is not a time.
            if (parsed.word && !anchored) return null
            var total = parsed.hour * 60 + parsed.minute + offset
            if (total < 0) total += 24 * 60
            if (parsed.clock24 && meridiem != null) meridiem = null
            val h = total / 60 % 24
            // "Paune ek" is 12:45, not 0:45.
            set(if (h == 0 && !parsed.clock24) 12 else h, total % 60, twentyFour = parsed.clock24)
            return j
        }

        /** "dedh baje" (1:30), "dhai baje" (2:30). */
        private fun fixed(w: List<String>, j: Int, h: Int): Int {
            set(h, 30, twentyFour = false)
            anchored = true
            var k = j + 1
            if (w.getOrNull(k) in OCLOCK) k++
            return k
        }

        private fun set(h: Int, m: Int, twentyFour: Boolean) {
            hour = h
            minute = m
            clock24 = twentyFour
        }
    }

    private class DurationPart(val amount: Duration, val unit: Duration, val next: Int)

    private fun durationPart(w: List<String>, j: Int): DurationPart? {
        SPECIAL_DURATIONS.firstOrNull { w.matches(j, it.first) }?.let { (phrase, minutes) ->
            return DurationPart(Duration.ofMinutes(minutes), Duration.ofHours(1), j + phrase.size)
        }
        var k = j
        val amount: Double = if (w.matches(k, "a", "couple", "of")) {
            k += 3
            2.0
        } else {
            val token = w.getOrNull(k) ?: return null
            val number = token.toDoubleOrNull()?.takeIf { it > 0 && token.all { c -> c.isDigit() || c == '.' } }
                ?: NUMBER_WORDS[token]?.let { tens ->
                    // "twenty five minutes"
                    val ones = w.getOrNull(k + 1)?.let { NUMBER_WORDS[it] }
                    if (tens % 10 == 0 && tens >= 20 && ones != null && ones in 1..9) {
                        k++
                        (tens + ones).toDouble()
                    } else {
                        tens.toDouble()
                    }
                }
                ?: return null
            k++
            number
        }
        val unit = w.getOrNull(k)?.let { UNITS[it] } ?: return null
        val millis = (unit.toMillis() * amount).toLong()
        return DurationPart(Duration.ofMillis(millis), unit, k + 1)
    }

    private class ClockNumber(val hour: Int, val minute: Int, val clock24: Boolean, val word: Boolean = false)

    private val CLOCK = Regex("""(\d{1,2})(?:[:.](\d{2}))?""")

    private fun clockNumber(token: String): ClockNumber? {
        val match = CLOCK.matchEntire(token) ?: return null
        val hourText = match.groupValues[1]
        val h = hourText.toInt()
        val m = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || m > 59) return null
        // "16:30", "0:15" and "07:00" are 24-hour times.
        val clock24 = h > 12 || h == 0 || (hourText.length == 2 && hourText[0] == '0')
        return ClockNumber(h, m, clock24)
    }

    private fun List<String>.matches(at: Int, vararg phrase: String): Boolean =
        at >= 0 && at + phrase.size <= size && phrase.indices.all { this[at + it] == phrase[it] }

    private fun List<String>.matches(at: Int, phrase: List<String>): Boolean =
        at >= 0 && at + phrase.size <= size && phrase.indices.all { this[at + it] == phrase[it] }

    private fun words(value: String) = value.split(' ').map(::nfc)

    private class PeriodWord(val phrase: List<String>, val period: DayPeriod, val today: Boolean)

    private val MIN_DELAY: Duration = Duration.ofSeconds(5)
    private val MAX_DELAY: Duration = Duration.ofDays(7)

    private val FILLERS = nfcSetOf("ko", "को", "pe", "par", "पर", "sharp", "exactly", "precisely", "the")
    private val DELAY_ENDINGS = nfcSetOf("later", "baad", "bad", "mein", "me", "mai", "बाद", "में", "मे")
    private val AM_WORDS = setOf("am", "a.m")
    private val PM_WORDS = setOf("pm", "p.m")
    private val OCLOCK = nfcSetOf("o'clock", "oclock", "baje", "bje", "baj", "बजे", "hours", "hrs")
    private val HALF_PAST_HINDI = nfcSetOf("saade", "sade", "saadhe", "sadhe", "साढ़े", "साढे")
    private val QUARTER_PAST_HINDI = nfcSetOf("sawa", "sava", "सवा")
    private val QUARTER_TO_HINDI = nfcSetOf("paune", "pone", "पौने")
    private val ONE_THIRTY = nfcSetOf("dedh", "derh", "डेढ़", "डेढ")
    private val TWO_THIRTY = nfcSetOf("dhai", "dhaai", "ढाई")
    private val NAMED_TIMES = mapOf(
        "noon" to (12 to 0), "midday" to (12 to 0), "midnight" to (0 to 0),
    )

    private val DAY_WORDS: List<Pair<List<String>, Int>> = listOf(
        "the day after tomorrow" to 2, "day after tomorrow" to 2, "parson" to 2, "parso" to 2, "परसों" to 2,
        "today" to 0, "aaj" to 0, "आज" to 0,
        "tomorrow" to 1, "tmrw" to 1, "tomorow" to 1, "tommorow" to 1, "tommorrow" to 1, "kal" to 1, "कल" to 1,
    ).map { (phrase, offset) -> words(phrase) to offset }.sortedByDescending { it.first.size }

    private val PERIOD_WORDS: List<PeriodWord> = listOf(
        Triple("this morning", DayPeriod.Morning, true), Triple("in the morning", DayPeriod.Morning, false),
        Triple("morning", DayPeriod.Morning, false), Triple("subah", DayPeriod.Morning, false),
        Triple("subha", DayPeriod.Morning, false), Triple("sawere", DayPeriod.Morning, false),
        Triple("savere", DayPeriod.Morning, false), Triple("सुबह", DayPeriod.Morning, false),
        Triple("सवेरे", DayPeriod.Morning, false),
        Triple("this afternoon", DayPeriod.Afternoon, true), Triple("in the afternoon", DayPeriod.Afternoon, false),
        Triple("afternoon", DayPeriod.Afternoon, false), Triple("dopahar", DayPeriod.Afternoon, false),
        Triple("dopehar", DayPeriod.Afternoon, false), Triple("dophar", DayPeriod.Afternoon, false),
        Triple("दोपहर", DayPeriod.Afternoon, false),
        Triple("this evening", DayPeriod.Evening, true), Triple("in the evening", DayPeriod.Evening, false),
        Triple("evening", DayPeriod.Evening, false), Triple("shaam", DayPeriod.Evening, false),
        Triple("sham", DayPeriod.Evening, false), Triple("शाम", DayPeriod.Evening, false),
        Triple("tonight", DayPeriod.Night, true), Triple("at night", DayPeriod.Night, false),
        Triple("in the night", DayPeriod.Night, false), Triple("night", DayPeriod.Night, false),
        Triple("raat", DayPeriod.Night, false), Triple("रात", DayPeriod.Night, false),
    ).map { (phrase, period, today) -> PeriodWord(words(phrase), period, today) }.sortedByDescending { it.phrase.size }

    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg names: String) = names.forEach { put(nfc(it), day) }
        add(DayOfWeek.MONDAY, "monday", "mon", "somvar", "somwar", "सोमवार")
        add(DayOfWeek.TUESDAY, "tuesday", "tue", "tues", "mangalvar", "mangalwar", "मंगलवार")
        add(DayOfWeek.WEDNESDAY, "wednesday", "wed", "budhvar", "budhwar", "बुधवार")
        add(DayOfWeek.THURSDAY, "thursday", "thu", "thur", "thurs", "guruvar", "guruwar", "गुरुवार", "वीरवार")
        add(DayOfWeek.FRIDAY, "friday", "fri", "shukravar", "shukrawar", "शुक्रवार")
        add(DayOfWeek.SATURDAY, "saturday", "sat", "shanivar", "shaniwar", "शनिवार")
        add(DayOfWeek.SUNDAY, "sunday", "sun", "ravivar", "raviwar", "itvaar", "itwar", "रविवार", "इतवार")
    }

    private val UNITS: Map<String, Duration> = buildMap {
        listOf("s", "sec", "secs", "second", "seconds", "सेकंड").forEach { put(nfc(it), Duration.ofSeconds(1)) }
        listOf("m", "min", "mins", "minute", "minutes", "minat", "minit", "मिनट", "मिनिट").forEach { put(nfc(it), Duration.ofMinutes(1)) }
        listOf("h", "hr", "hrs", "hour", "hours", "ghanta", "ghante", "ghanton", "घंटा", "घंटे", "घंटों")
            .forEach { put(nfc(it), Duration.ofHours(1)) }
    }

    private val SPECIAL_DURATIONS: List<Pair<List<String>, Long>> = listOf(
        "half an hour" to 30L, "half hour" to 30L, "a half hour" to 30L, "aadha ghanta" to 30L, "aadhe ghante" to 30L,
        "adha ghanta" to 30L, "adhe ghante" to 30L, "आधा घंटा" to 30L, "आधे घंटे" to 30L,
        "dedh ghanta" to 90L, "dedh ghante" to 90L, "डेढ़ घंटा" to 90L, "डेढ़ घंटे" to 90L,
        "dhai ghante" to 150L, "ढाई घंटे" to 150L,
    ).map { (phrase, minutes) -> words(phrase) to minutes }.sortedByDescending { it.first.size }

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6,
        "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12,
        "pandrah" to 15, "bees" to 20, "pachees" to 25, "pachchis" to 25, "tees" to 30, "chalis" to 40,
        "chaalis" to 40, "pachas" to 50, "pachaas" to 50,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छह" to 6, "सात" to 7, "आठ" to 8,
        "नौ" to 9, "दस" to 10, "पंद्रह" to 15, "बीस" to 20, "तीस" to 30, "चालीस" to 40, "पचास" to 50,
    ).mapKeys { nfc(it.key) }

    private val HOUR_WORDS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )
}
