package ai.wakey.android.agent

import java.text.Normalizer

/**
 * Matches whole-utterance simple commands (English, Hindi, Hinglish) so they run on-device with no
 * LLM call. Anything with a second clause or intent ("open Chrome and search for cats") returns
 * null and goes to the agent instead.
 */
object FastCommandRouter {

    fun route(utterance: String): FastCommand? {
        val words = stripFillers(tokenize(utterance))
        if (words.isEmpty()) return null
        val lower = words.map { it.lowercase() }
        if (lower.any { it in CONJUNCTIONS }) return null
        val phrase = lower.joinToString(" ")
        when (phrase) {
            in HOME_PHRASES -> return FastCommand.GoHome
            in BACK_PHRASES -> return FastCommand.GoBack
        }
        torch(lower)?.let { return FastCommand.Torch(it) }
        return openApp(words, lower)
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

    private fun stripFillers(words: List<String>): List<String> {
        var result = words
        var changed = true
        while (changed && result.isNotEmpty()) {
            changed = false
            val lower = result.map { it.lowercase() }
            LEADING_FILLERS.firstOrNull { lower.startsWith(it) }?.let {
                result = result.drop(it.size)
                changed = true
            }
            TRAILING_FILLERS.firstOrNull { lower.endsWith(it) }?.let {
                result = result.dropLast(it.size)
                changed = true
            }
        }
        return result
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

    private fun openApp(words: List<String>, lower: List<String>): FastCommand.OpenApp? {
        val range = OPEN_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { it.size until words.size }
            ?: OPEN_SUFFIXES.firstOrNull { lower.endsWith(it) }?.let { 0 until words.size - it.size }
            ?: return null
        var name = words.slice(range)
        while (name.isNotEmpty() && name.first().lowercase() in NAME_ARTICLES) name = name.drop(1)
        while (name.isNotEmpty() && name.last().lowercase() in NAME_TRAILERS) name = name.dropLast(1)
        if (name.isEmpty() || name.size > MAX_APP_NAME_WORDS) return null
        val key = name.joinToString(" ") { it.lowercase() }
        if (key !in APP_NAMES_WITH_INTENT_WORDS && key.split(' ').any { it in INTENT_WORDS }) return null
        return FastCommand.OpenApp(name.joinToString(" "))
    }

    private fun List<String>.startsWith(prefix: List<String>, at: Int = 0): Boolean =
        at >= 0 && at + prefix.size <= size && prefix.indices.all { this[at + it] == prefix[it] }

    private fun List<String>.endsWith(suffix: List<String>): Boolean = startsWith(suffix, size - suffix.size)

    private fun phrases(vararg values: String): List<List<String>> =
        values.map { it.split(' ') }.sortedByDescending { it.size }

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val ZERO_WIDTH = Regex("[\\u200C\\u200D]")
    private const val MAX_APP_NAME_WORDS = 4

    private val LEADING_FILLERS = phrases(
        "hey wakey", "hi wakey", "ok wakey", "okay wakey", "wakey", "hey", "hi", "ok", "okay", "please", "pls",
        "plz", "kindly", "can you", "could you", "would you", "will you", "now", "just", "zara", "jara",
        "कृपया", "ज़रा", "जरा", "प्लीज़", "प्लीज", "अब",
    )
    private val TRAILING_FILLERS = phrases(
        "please", "pls", "plz", "now", "right now", "for me", "na", "zara", "abhi", "jaldi", "प्लीज़", "प्लीज",
        "कृपया", "ना", "अभी", "जल्दी",
    )

    /** A second clause means the request is not a single fast command. */
    private val CONJUNCTIONS = setOf("and", "then", "also", "after", "aur", "phir", "fir", "और", "फिर", "तब", "बाद")

    /** Words that signal a task inside what would otherwise be an app name ("open YouTube play songs"). */
    private val INTENT_WORDS = setOf(
        "search", "find", "look", "send", "call", "dial", "message", "text", "type", "write", "play", "post",
        "share", "tell", "show", "set", "book", "order", "buy", "pay", "for", "to", "with", "about", "in", "from",
        "a", "an", "khojo", "dhundo", "dhoondo", "bhejo", "likho", "chalao", "खोजो", "ढूंढो", "ढूँढो", "भेजो",
        "लिखो", "कॉल", "मैसेज", "चलाओ",
    )
    private val APP_NAMES_WITH_INTENT_WORDS = setOf("play store", "google play", "google play store", "play games")

    private val HOME_PHRASES = setOf(
        "go home", "home", "home screen", "homescreen", "go to home", "go to home screen", "go to the home screen",
        "go to homescreen", "go to the homescreen", "take me home", "return home", "press home", "open home screen",
        "open the home screen", "show home screen", "show the home screen", "home jao", "home pe jao", "home par jao",
        "home screen pe jao", "home screen par jao", "होम", "होम स्क्रीन", "होम जाओ", "होम पर जाओ", "होम स्क्रीन पर जाओ",
    )
    private val BACK_PHRASES = setOf(
        "go back", "back", "press back", "navigate back", "go back once", "back jao", "wapas jao", "vapas jao",
        "peeche jao", "piche jao", "वापस", "वापस जाओ", "पीछे", "पीछे जाओ", "बैक",
    )

    private val TORCH_NOUNS = phrases(
        "flashlight", "flash light", "torch", "torchlight", "torch light", "flash", "टॉर्च", "टार्च", "फ्लैशलाइट",
        "फ्लैश लाइट", "फ़्लैशलाइट", "फ्लैश",
    )
    private val TORCH_ARTICLES = setOf("the", "my", "phone", "ko", "को")
    private val TORCH_ON = setOf(
        "on", "turn on", "switch on", "put on", "enable", "activate", "start", "open", "jalao", "jala do", "jala",
        "jalado", "jalaiye", "jala dijiye", "on karo", "on kar do", "on kardo", "on kariye", "on kijiye",
        "chalu karo", "chalu kar do", "chalu kardo", "chalu", "chalu kijiye", "start karo", "जलाओ", "जला दो",
        "जलादो", "जलाइए", "जला दीजिए", "चालू करो", "चालू कर दो", "चालू कीजिए", "चालू", "ऑन करो", "ऑन कर दो",
        "ऑन", "on करो", "on कर दो",
    )
    private val TORCH_OFF = setOf(
        "off", "turn off", "switch off", "disable", "deactivate", "stop", "close", "band karo", "band kar do",
        "band kardo", "band", "band kijiye", "bujhao", "bujha do", "off karo", "off kar do", "off kardo",
        "बंद करो", "बंद कर दो", "बंद", "बंद कीजिए", "बुझाओ", "बुझा दो", "ऑफ करो", "ऑफ कर दो", "ऑफ", "off करो",
    )

    private val OPEN_PREFIXES = phrases("open", "open up", "launch", "start")
    private val OPEN_SUFFIXES = phrases(
        "kholo", "khol do", "khol", "kholiye", "khol dijiye", "open karo", "open kar do", "open kardo",
        "खोलो", "खोल दो", "खोलिए", "खोल दीजिए", "ओपन करो", "ओपन कर दो", "open करो",
    )
    private val NAME_ARTICLES = setOf("the", "my")
    private val NAME_TRAILERS = setOf("app", "application", "ko", "को", "ऐप", "एप")
}
