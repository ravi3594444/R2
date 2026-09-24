package ai.wakey.android.core

import ai.wakey.android.agent.FastCommandRouter

/**
 * The wake phrase inside a Deepgram transcript. The audio sent to Deepgram starts just before the
 * wake phrase, so a transcript usually opens with it ("Hey Wakey, turn on the torch"), often
 * misheard ("Hey Vicky", "Hey Becky", "हे वेकी").
 */
internal object WakeTranscript {
    enum class Verdict { Heard, NotHeard, Undecided }

    /** Removes a leading wake phrase ("Hey Wakey, …") that the pre-roll audio may include. */
    fun strip(text: String, wakePhrase: String): String {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val wakeWords = wakeWords(wakePhrase)
        if (words.isEmpty() || wakeWords.isEmpty()) return text.trim()
        var i = 0
        var matched = 0
        // Allow the transcript to start mid-phrase (e.g. only "Wakey," survived the pre-roll).
        while (i < words.size && matched < wakeWords.size) {
            val w = norm(words[i])
            val idx = wakeWords.indexOfFirst { similar(it, w) }
            if (idx < 0 || idx < matched) break
            matched = idx + 1
            i++
        }
        return if (i == 0) text.trim() else words.drop(i).joinToString(" ").trimStart(',', '.', ' ')
    }

    /**
     * Confirms an unsure on-device detection: does [text] open with the wake phrase? Only its
     * distinctive word counts ("Wakey" in "Hey Wakey"; "hey" alone is everyday speech), within the
     * phrase's length plus one word. [Verdict.Undecided] while an interim transcript is too short
     * to tell.
     */
    fun check(text: String, wakePhrase: String, isFinal: Boolean): Verdict {
        val wake = wakeWords(wakePhrase)
        if (wake.isEmpty()) return Verdict.Heard
        val name = wake.maxBy { it.length }
        val joined = wake.joinToString("")
        val words = text.split(WORD_BREAK).map(::norm).filter { it.isNotEmpty() }
        val window = wake.size + 1
        val heard = words.take(window).any { closeTo(name, it) || (wake.size > 1 && closeTo(joined, it)) }
        return when {
            heard -> Verdict.Heard
            isFinal || words.size > window -> Verdict.NotHeard
            else -> Verdict.Undecided
        }
    }

    /**
     * Confirms a "hey" detection in [ai.wakey.android.config.WakeMode.HeyCommand]: the transcript
     * must open with "hey" and go on with something addressed to a phone: a direct command Wakey
     * knows ("hey torch jalao"), an action verb up front ("hey, open Instagram", "hey can you call
     * Mom") or a Hindi action verb anywhere ("hey Instagram kholo"). "Hey Wakey …" always counts.
     * Everyday "hey, how are you" does not. Undecided until a command shows or the turn ends.
     */
    fun checkHeyCommand(text: String, isFinal: Boolean): Verdict {
        val words = text.split(WORD_BREAK).map(::norm).filter { it.isNotEmpty() }
        val heyAt = words.take(2).indexOfFirst { it in HEY_WORDS }
        if (heyAt < 0) return if (words.isEmpty() && !isFinal) Verdict.Undecided else Verdict.NotHeard
        var rest = words.drop(heyAt + 1)
        if (rest.firstOrNull()?.let { closeTo("wakey", it) } == true) return Verdict.Heard
        while (rest.isNotEmpty() && rest.first() in POLITE_WORDS) rest = rest.drop(1)
        val command = rest.isNotEmpty() && (
            rest.first() in ACTION_VERBS ||
                rest.any { word -> HINDI_VERBS.any { word.startsWith(it) } || DEVANAGARI_VERBS.any { word.startsWith(it) } } ||
                FastCommandRouter.route(rest.joinToString(" ")) != null
            )
        return when {
            command -> Verdict.Heard
            isFinal -> Verdict.NotHeard
            else -> Verdict.Undecided
        }
    }

    private fun wakeWords(phrase: String) = phrase.lowercase().split(' ').filter { it.isNotEmpty() }

    private fun norm(w: String) = w.lowercase().trim(',', '.', '!', '?', '।', ':', ';', '"', '\'')

    /** Loose, for stripping: a request rarely starts with a word two letters away from the phrase. */
    private fun similar(a: String, b: String): Boolean {
        if (a == b || misheard(a, b)) return true
        if (a.length < 2 || b.length < 2) return false
        return levenshtein(a, b) <= if (a.length <= 3) 1 else 2
    }

    /** Strict, for confirming a detection: "make" must not pass for "wakey". */
    private fun closeTo(a: String, b: String): Boolean {
        if (a == b || misheard(a, b)) return true
        if (b.length < 2) return false
        return levenshtein(a, b) <= maxOf(1, a.length / 4)
    }

    // Flux often hears "Wakey" as Becky, Vicky, Wiki, Ricky or waking, or writes it in Devanagari.
    private fun misheard(a: String, b: String) = a == "wakey" && (MISHEARD_WAKEY.matches(b) || DEVANAGARI_WAKEY.matches(b))

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private val HEY_WORDS = setOf("hey", "hay", "hei", "he", "heyy", "हे", "हेय")

    /** Skipped between "hey" and the command: "hey, please open…", "hey can you open…". */
    private val POLITE_WORDS = setOf(
        "please", "pls", "can", "could", "would", "will", "you", "u", "kindly", "zara", "jara", "just", "quickly",
        // Habit from other assistants: "hey Google, Instagram kholo".
        "google",
    )

    private val ACTION_VERBS = setOf(
        "open", "launch", "start", "close", "search", "find", "look", "play", "pause", "resume", "call", "dial",
        "message", "text", "send", "reply", "turn", "switch", "set", "show", "take", "go", "navigate", "scroll",
        "tap", "click", "press", "read", "mute", "unmute", "increase", "decrease", "raise", "lower", "enable",
        "disable", "check", "book", "order", "share", "save", "delete", "record", "capture", "add", "remove",
        "install", "download", "create", "write", "type", "make", "remind", "lock", "unlock", "connect", "disconnect",
        "flashlight", "torch", "bluetooth", "wifi", "volume",
    )

    /** Hinglish action verbs, matched as word prefixes (kholo, kholiye, kholna…). */
    private val HINDI_VERBS = listOf(
        "khol", "karo", "kariye", "kardo", "chala", "dikha", "band", "bajao", "baja", "lagao", "laga",
        "jalao", "jala", "bujhao", "bujha", "bhejo", "bhej", "dhundo", "dhoondo", "dhundh", "likho", "sunao", "badhao", "ghatao",
    )
    private val DEVANAGARI_VERBS = listOf("खोल", "करो", "करिए", "चला", "दिखा", "बंद", "बजा", "लगा", "जला", "बुझा", "भेज", "ढूंढ", "लिख", "सुना")

    private val WHITESPACE = Regex("\\s+")
    private val WORD_BREAK = Regex("[\\s-]+")
    private val MISHEARD_WAKEY = Regex("[bvwr][aeiouy]+(?:ck|kk|k|c|q|gg|g)(?:ey|ie|ee|y|i|ing|in|en)")

    /** वेकी, वाकी, बेकी, विकी, रिकी, वेगी…: व/ब/र, a vowel sign, क or ग, a vowel sign. */
    private val DEVANAGARI_WAKEY = Regex("[वबर]\\p{M}*[कग]\\p{M}*(?:[कगय]\\p{M}*)?")
}
