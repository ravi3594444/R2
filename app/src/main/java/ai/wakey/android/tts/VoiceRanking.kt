package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import android.speech.tts.Voice
import java.util.Locale

/** Plain snapshot of an Android [Voice], so voice selection runs in JVM unit tests. */
internal data class VoiceInfo(
    val name: String,
    /** BCP-47 tag such as "en-IN". */
    val languageTag: String,
    /** One of the `Voice.QUALITY_*` values. */
    val quality: Int,
    /** One of the `Voice.LATENCY_*` values. */
    val latency: Int,
    val networkRequired: Boolean,
    val installed: Boolean,
)

/** Chooses and labels Android voices: offline first, Hindi for Hindi text, otherwise Indian, US, then UK English. */
internal object VoiceRanking {
    const val HINDI = "hi-IN"
    private const val DEFAULT_ENGLISH = "en-IN"
    private val ENGLISH_ORDER = listOf("en-IN", "en-US", "en-GB")
    private val PICKER_ORDER = ENGLISH_ORDER + "hi"

    /** The locale to speak [text] in: Hindi for Devanagari or a Hindi hint, the hinted English variant, else Indian English. */
    fun targetLanguage(text: String, languageTag: String?): String = when {
        SpeechText.hasDevanagari(text) || languageTag?.let(::language) == "hi" -> HINDI
        languageTag != null && language(languageTag) == "en" && '-' in languageTag -> languageTag
        else -> DEFAULT_ENGLISH
    }

    /**
     * The voice for [target]: [preferredName] when it is installed and speaks the target language, otherwise the best
     * ranked voice. Null when no installed voice speaks the language.
     */
    fun select(voices: List<VoiceInfo>, target: String, preferredName: String?): VoiceInfo? {
        val preferred = preferredName?.let { name ->
            voices.firstOrNull { it.name == name && it.installed && language(it.languageTag) == language(target) }
        }
        return preferred ?: rank(voices, target).firstOrNull()
    }

    /** Installed voices for [target]'s language, best first: offline, closest locale, highest quality, lowest latency. */
    fun rank(voices: List<VoiceInfo>, target: String): List<VoiceInfo> = ranked(voices, localeOrder(target))

    /** Picker entries for Indian, US and UK English and Hindi, offline first, with readable, distinct labels. */
    fun options(voices: List<VoiceInfo>): List<VoiceOption> {
        val listed = ranked(voices, PICKER_ORDER)
        val labels = listed.map(::label)
        val totals = labels.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return listed.mapIndexed { i, voice ->
            val base = labels[i]
            val label = if (totals.getValue(base) > 1) {
                val n = (seen[base] ?: 0) + 1
                seen[base] = n
                "$base · voice $n"
            } else {
                base
            }
            VoiceOption(voice.name, label, voice.languageTag, offline = !voice.networkRequired, engine = TtsEngine.Android)
        }
    }

    /** E.g. "English (India) · offline · high quality". */
    fun label(voice: VoiceInfo): String {
        val locale = Locale.forLanguageTag(voice.languageTag).getDisplayName(Locale.ENGLISH)
        val where = if (voice.networkRequired) "needs internet" else "offline"
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
            voice.quality >= Voice.QUALITY_HIGH -> "high quality"
            voice.quality >= Voice.QUALITY_NORMAL -> "normal quality"
            voice.quality >= Voice.QUALITY_LOW -> "low quality"
            else -> "very low quality"
        }
        return "$locale · $where · $quality"
    }

    private fun ranked(voices: List<VoiceInfo>, order: List<String>): List<VoiceInfo> =
        voices.filter { it.installed }
            .mapNotNull { v -> localeRank(v.languageTag, order)?.let { position -> v to position } }
            .sortedWith(compareBy({ it.first.networkRequired }, { it.second }, { -it.first.quality }, { it.first.latency }, { it.first.name }))
            .map { it.first }

    private fun localeOrder(target: String): List<String> =
        if (language(target) == "hi") listOf(HINDI, "hi") else (listOf(target) + ENGLISH_ORDER + "en").distinct()

    /** Position of the first entry of [order] that [tag] satisfies; a bare language ("en") matches any region. */
    private fun localeRank(tag: String, order: List<String>): Int? = order.indexOfFirst { wanted ->
        tag.equals(wanted, ignoreCase = true) || ('-' !in wanted && language(tag) == wanted)
    }.takeIf { it >= 0 }

    private fun language(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()
}
