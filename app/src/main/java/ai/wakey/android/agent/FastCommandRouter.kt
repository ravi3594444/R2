package ai.wakey.android.agent

import java.text.Normalizer

/**
 * Matches whole-utterance simple commands (English, Hindi, Hinglish) so they run on-device with no
 * LLM call. Anything with a second clause or intent ("open Chrome and search for cats") returns
 * null and goes to the agent instead.
 *
 * Words are compared in one spoken form ([SPOKEN_FORMS]): speech-to-text writes the same command
 * in Latin or Devanagari script, often mixed ("torch जलाओ", "Slashlight band करो"), and mishears
 * Hinglish verbs ("YouTube Colo" for kholo). App names keep the words as heard, for [AppMatcher].
 */
object FastCommandRouter {

    fun route(utterance: String): FastCommand? {
        val heard = tokenize(utterance)
        val allSpoken = heard.map(::spokenForm)
        val kept = withoutFillers(allSpoken)
        if (kept.isEmpty()) return null
        val words = heard.slice(kept)
        val spoken = allSpoken.slice(kept)
        if (spoken.any { it in CONJUNCTIONS }) return null
        when (spoken.joinToString(" ")) {
            in HOME_PHRASES -> return FastCommand.GoHome
            in BACK_PHRASES -> return FastCommand.GoBack
        }
        torch(spoken)?.let { return FastCommand.Torch(it) }
        return openApp(words, spoken)
    }

    /**
     * Words with punctuation removed. Devanagari vowel signs are marks, not punctuation, so they stay;
     * NFC and dropping zero-width joiners make keyboard and STT spellings of the same word compare equal.
     */
    private fun tokenize(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(ZERO_WIDTH, "")
            .replace("&", " and ")
            .replace(NON_WORD, " ")
            .split(' ')
            .filter { it.isNotEmpty() }

    private fun spokenForm(word: String): String {
        val lower = word.lowercase()
        SPOKEN_FORMS[lower]?.let { return it }
        // "Clashlight", "Slashlight": one or two letters off a long torch noun.
        if (lower.length >= 8) LONG_TORCH_NOUNS.firstOrNull { AppMatcher.editDistance(lower, it) <= 2 }?.let { return it }
        return lower
    }

    /** Indices of [words] left once leading and trailing fillers and a leftover wake word are dropped. */
    private fun withoutFillers(words: List<String>): IntRange {
        var start = 0
        var end = words.size
        var changed = true
        while (changed && start < end) {
            changed = false
            val rest = words.subList(start, end)
            val lead = LEADING_FILLERS.firstOrNull { rest.startsWith(it) }?.size
                ?: 1.takeIf { rest.size > 1 && WAKE_NAME.matches(rest[0]) }
            if (lead != null) {
                start += lead
                changed = true
                continue
            }
            TRAILING_FILLERS.firstOrNull { rest.endsWith(it) }?.let {
                end -= it.size
                changed = true
            }
        }
        return start until end
    }

    /** True/false for a torch on/off command, null if [words] is not exactly one. */
    private fun torch(words: List<String>): Boolean? {
        val nounAt = words.indices.firstOrNull { i -> TORCH_NOUNS.any { words.startsWith(it, i) } } ?: return null
        val noun = TORCH_NOUNS.first { words.startsWith(it, nounAt) }
        val rest = (words.take(nounAt) + words.drop(nounAt + noun.size)).filterNot { it in TORCH_ARTICLES }
        return when (rest.joinToString(" ")) {
            in TORCH_ON -> true
            in TORCH_OFF -> false
            else -> null
        }
    }

    private fun openApp(words: List<String>, spoken: List<String>): FastCommand.OpenApp? {
        val range = OPEN_PREFIXES.firstOrNull { spoken.startsWith(it) }?.let { it.size until words.size }
            ?: OPEN_SUFFIXES.firstOrNull { spoken.endsWith(it) }?.let { 0 until words.size - it.size }
            ?: return null
        var first = range.first
        var last = range.last
        while (first <= last && spoken[first] in NAME_ARTICLES) first++
        while (first <= last && spoken[last] in NAME_TRAILERS) last--
        if (first > last || last - first + 1 > MAX_APP_NAME_WORDS) return null
        val key = spoken.slice(first..last).joinToString(" ")
        if (key !in APP_NAMES_WITH_INTENT_WORDS && key.split(' ').any { it in INTENT_WORDS }) return null
        return FastCommand.OpenApp(words.slice(first..last).joinToString(" "))
    }

    private fun List<String>.startsWith(prefix: List<String>, at: Int = 0): Boolean =
        at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    private fun List<String>.endsWith(suffix: List<String>): Boolean = startsWith(suffix, size - suffix.size)

    private fun phrases(vararg values: String): List<List<String>> =
        values.map { it.split(' ') }.sortedByDescending { it.size }

    private fun spokenForms(vararg forms: Pair<String, List<String>>): Map<String, String> =
        forms.flatMap { (spoken, variants) -> variants.map { Normalizer.normalize(it, Normalizer.Form.NFC) to spoken } }.toMap()

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val ZERO_WIDTH = Regex("[\\u200C\\u200D]")
    private const val MAX_APP_NAME_WORDS = 4

    /**
     * Other spellings of a word → the spoken form the phrase lists below use: Devanagari spellings
     * (Flux writes English words in Devanagari when it hears Hindi), romanisation variants, and
     * mis-hearings seen from Deepgram Flux on Indian-English Hinglish commands.
     */
    private val SPOKEN_FORMS: Map<String, String> = spokenForms(
        // Hindi verbs and particles.
        "karo" to listOf("caro", "carro", "kro", "करो"),
        "kar" to listOf("कर"),
        "do" to listOf("दो"),
        "de" to listOf("दे"),
        "kijiye" to listOf("kijie", "कीजिए", "कीजिये"),
        "kariye" to listOf("करिए", "करिये"),
        "dijiye" to listOf("dijie", "दीजिए", "दीजिये"),
        "kholo" to listOf("colo", "kolo", "kohlo", "khollo", "holo", "खोलो"),
        "khol" to listOf("खोल"),
        "kholiye" to listOf("खोलिए", "खोलिये"),
        "jalao" to listOf("jalaao", "jalau", "जलाओ", "जलाऊ"),
        "jala" to listOf("जला"),
        "jalado" to listOf("जलादो"),
        "jalaiye" to listOf("जलाइए", "जलाइये"),
        "chalao" to listOf("चलाओ"),
        "chalu" to listOf("chaalu", "चालू"),
        "band" to listOf("bandh", "बंद", "बन्द"),
        "bujhao" to listOf("बुझाओ"),
        "bujha" to listOf("बुझा"),
        "jao" to listOf("जाओ"),
        "par" to listOf("पर"),
        "pe" to listOf("पे"),
        "ko" to listOf("को"),
        "ki" to listOf("की"),
        "ka" to listOf("का"),
        "wapas" to listOf("vapas", "वापस"),
        "peeche" to listOf("piche", "पीछे"),
        "aur" to listOf("और"),
        "phir" to listOf("fir", "फिर"),
        "tab" to listOf("तब"),
        "baad" to listOf("बाद"),
        "khojo" to listOf("खोजो"),
        "dhundo" to listOf("dhoondo", "ढूंढो", "ढूँढो"),
        "bhejo" to listOf("भेजो"),
        "likho" to listOf("लिखो"),
        // English command words written in Devanagari.
        "and" to listOf("एंड"),
        "then" to listOf("देन"),
        "on" to listOf("ऑन", "ओन"),
        "off" to listOf("ऑफ", "ऑफ़", "ओफ"),
        "open" to listOf("ओपन"),
        "turn" to listOf("टर्न"),
        "switch" to listOf("स्विच"),
        "launch" to listOf("लॉन्च"),
        "start" to listOf("स्टार्ट"),
        "the" to listOf("द", "दि"),
        "torch" to listOf("टॉर्च", "टार्च", "टोर्च", "तोड़", "dodge"),
        "flashlight" to listOf("फ्लैशलाइट", "फ़्लैशलाइट", "फ्लेशलाइट", "फ्लैशलाईट"),
        "flash" to listOf("फ्लैश", "फ़्लैश"),
        "light" to listOf("लाइट", "लाईट"),
        "phone" to listOf("फोन", "फ़ोन"),
        "app" to listOf("ऐप", "एप", "ऍप"),
        "home" to listOf("होम"),
        "screen" to listOf("स्क्रीन"),
        "back" to listOf("बैक"),
        "call" to listOf("कॉल"),
        "message" to listOf("मैसेज"),
        "search" to listOf("सर्च"),
        // Politeness, greetings and the wake word.
        "please" to listOf("pls", "plz", "प्लीज़", "प्लीज", "कृपया"),
        "zara" to listOf("jara", "ज़रा", "जरा"),
        "ab" to listOf("अब"),
        "abhi" to listOf("अभी"),
        "jaldi" to listOf("जल्दी"),
        "na" to listOf("ना"),
        "yaar" to listOf("yar", "यार"),
        "ji" to listOf("जी"),
        "bhai" to listOf("bhaiya", "भाई", "भैया"),
        "hey" to listOf("हे", "हेय"),
        "hi" to listOf("हाय"),
        "ok" to listOf("okay", "ओके"),
        "wakey" to listOf("वेकी", "वाकी", "वेकि", "वैकी"),
    )

    private val LONG_TORCH_NOUNS = listOf("flashlight", "torchlight")

    /** "Wakey" as speech-to-text mishears it after the wake phrase: Becky, Vicky, Wiki, waking, … */
    private val WAKE_NAME = Regex("[bvw][aeiouy]+(?:ck|kk|k|c|q)(?:ey|ie|ee|y|i|ing|in|en)")

    private val LEADING_FILLERS = phrases(
        "hey wakey", "hi wakey", "ok wakey", "wakey", "hey", "hi", "hello", "ok", "please", "kindly", "can you",
        "could you", "would you", "will you", "now", "just", "zara", "ab", "yaar", "bhai", "ji",
    )
    private val TRAILING_FILLERS = phrases(
        "please", "now", "right now", "for me", "na", "zara", "abhi", "jaldi", "yaar", "ji", "bhai", "thanks",
        "thank you", "ok",
    )

    /** A second clause means the request is not a single fast command. */
    private val CONJUNCTIONS = setOf("and", "then", "also", "after", "or", "aur", "phir", "tab", "baad")

    /** Words that signal a task inside what would otherwise be an app name ("open YouTube play songs"). */
    private val INTENT_WORDS = setOf(
        "search", "find", "look", "send", "call", "dial", "message", "text", "type", "write", "play", "post",
        "share", "tell", "show", "set", "book", "order", "buy", "pay", "for", "to", "with", "about", "in", "from",
        "a", "an", "khojo", "dhundo", "bhejo", "likho", "chalao",
    )
    private val APP_NAMES_WITH_INTENT_WORDS = setOf("play store", "google play", "google play store", "play games")

    private val HOME_PHRASES = setOf(
        "go home", "home", "home screen", "homescreen", "go to home", "go to home screen", "go to the home screen",
        "go to homescreen", "go to the homescreen", "take me home", "return home", "press home", "open home screen",
        "open the home screen", "show home screen", "show the home screen", "home jao", "home pe jao", "home par jao",
        "home screen pe jao", "home screen par jao",
    )
    private val BACK_PHRASES = setOf(
        "go back", "back", "press back", "navigate back", "go back once", "back jao", "wapas jao", "peeche jao",
        "wapas", "peeche",
    )

    private val TORCH_NOUNS = phrases("flashlight", "flash light", "torch", "torchlight", "torch light", "flash")
    /** "Open the flashlight app": the torch is not an app, but people ask for it as one. */
    private val TORCH_ARTICLES = setOf("the", "a", "my", "phone", "ko", "ki", "ka", "app", "application", "wala", "vala")
    private val TORCH_ON = setOf(
        "on", "turn on", "switch on", "put on", "enable", "activate", "start", "open", "open up", "launch", "light",
        "light up", "jalao", "jala do", "jala de", "jala", "jalado", "jalaiye", "jala dijiye", "chalao", "chala do",
        "on karo", "on kar do", "on kar de", "on kardo", "on kar", "on kariye", "on kijiye", "chalu karo",
        "chalu kar do", "chalu kar de", "chalu kardo", "chalu", "chalu kijiye", "start karo", "kholo", "khol do",
        "khol de", "khol", "kholiye", "open karo", "open kar do", "open kar de", "open kardo", "open kar",
        "start kar do",
    )
    private val TORCH_OFF = setOf(
        "off", "turn off", "turn of", "switch off", "switch of", "disable", "deactivate", "stop", "close", "shut",
        "band karo", "band kar do", "band kar de", "band kardo", "band kar", "band", "band kijiye", "ban karo",
        "ban kar do", "bujhao", "bujha do", "bujha de", "off karo", "off kar do", "off kar de", "off kardo", "off kar",
        "close karo", "close kar do", "stop karo",
    )

    private val OPEN_PREFIXES = phrases("open", "open up", "launch", "start")
    private val OPEN_SUFFIXES = phrases(
        "kholo", "khol do", "khol de", "khol", "kholiye", "khol dijiye", "open karo", "open kar do", "open kar de",
        "open kardo", "open kar",
    )
    private val NAME_ARTICLES = setOf("the", "my")
    private val NAME_TRAILERS = setOf("app", "application", "ko")
}
