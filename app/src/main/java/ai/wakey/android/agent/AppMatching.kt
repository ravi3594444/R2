package ai.wakey.android.agent

import java.text.Normalizer
import java.util.Locale

/** An installed app's launcher entry. */
internal data class LauncherApp(val label: String, val packageName: String, val activityName: String)

/**
 * A well-known app that may have a different label or package per phone maker. [names] are the
 * spoken forms (English and Hindi); [packages] are tried in order before [labels]. When nothing is
 * installed under those, [fallbackCategory] (a main-selector category) or [fallbackAction] is launched.
 */
internal data class AppAlias(
    val label: String,
    val names: List<String>,
    val packages: List<String>,
    val labels: List<String>,
    val fallbackAction: String? = null,
    val fallbackCategory: String? = null,
)

internal object AppAliases {
    val all = listOf(
        AppAlias(
            "Settings", listOf("settings", "setting", "सेटिंग्स", "सेटिंग", "सेटिंगस", "सेटिंग्ज़"), listOf("com.android.settings"),
            listOf("settings"), fallbackAction = "android.settings.SETTINGS",
        ),
        AppAlias(
            "Calculator", listOf("calculator", "calc", "कैलकुलेटर", "कैल्कुलेटर", "केलकुलेटर", "कैलक्यूलेटर"),
            listOf(
                "com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator",
                "com.oneplus.calculator", "com.coloros.calculator", "com.android.calculator2",
            ),
            listOf("calculator"), fallbackCategory = "android.intent.category.APP_CALCULATOR",
        ),
        AppAlias(
            "Camera", listOf("camera", "कैमरा", "केमरा"),
            listOf(
                "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oneplus.camera",
                "com.oplus.camera", "com.android.camera", "com.android.camera2",
            ),
            listOf("camera"), fallbackAction = "android.media.action.STILL_IMAGE_CAMERA",
        ),
        AppAlias(
            "Chrome", listOf("chrome", "google chrome", "क्रोम", "गूगल क्रोम"), listOf("com.android.chrome"), listOf("chrome"),
        ),
        AppAlias(
            "YouTube", listOf("youtube", "you tube", "यूट्यूब", "यू ट्यूब", "यूटूब"),
            listOf("com.google.android.youtube"), listOf("youtube"),
        ),
        AppAlias(
            "WhatsApp",
            listOf("whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "वॉट्सएप", "व्हाट्स ऐप"),
            listOf("com.whatsapp", "com.whatsapp.w4b"), listOf("whatsapp", "whatsapp business"),
        ),
        AppAlias(
            "Instagram", listOf("instagram", "insta", "इंस्टाग्राम", "इन्स्टाग्राम", "इंस्टा"), listOf("com.instagram.android"),
            listOf("instagram"),
        ),
        AppAlias("Facebook", listOf("facebook", "फेसबुक", "फ़ेसबुक"), listOf("com.facebook.katana"), listOf("facebook")),
        AppAlias(
            "Phone", listOf("phone", "dialer", "dialler", "dial pad", "dialpad", "फ़ोन", "फोन"),
            listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
            listOf("phone"), fallbackAction = "android.intent.action.DIAL",
        ),
        AppAlias(
            "Messages", listOf("messages", "messaging", "sms", "text messages", "मैसेज", "मैसेजेस"),
            listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
            listOf("messages", "messaging"), fallbackCategory = "android.intent.category.APP_MESSAGING",
        ),
        AppAlias("Gmail", listOf("gmail", "g mail", "जीमेल"), listOf("com.google.android.gm"), listOf("gmail")),
        AppAlias(
            "Maps", listOf("maps", "map", "google maps", "मैप्स", "मैप"), listOf("com.google.android.apps.maps"),
            listOf("maps"), fallbackCategory = "android.intent.category.APP_MAPS",
        ),
        AppAlias(
            "Play Store", listOf("play store", "playstore", "google play", "google play store", "प्ले स्टोर"),
            listOf("com.android.vending"), listOf("play store", "google play store"),
        ),
        AppAlias(
            "Photos", listOf("photos", "gallery", "google photos", "गैलरी", "फ़ोटो", "फोटो", "फोटोज़", "फोटोज"),
            listOf(
                "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery",
                "com.oneplus.gallery", "com.coloros.gallery3d", "com.android.gallery3d",
            ),
            listOf("photos", "gallery"), fallbackCategory = "android.intent.category.APP_GALLERY",
        ),
        AppAlias(
            "Clock", listOf("clock", "alarm", "alarms", "घड़ी", "क्लॉक", "अलार्म"),
            listOf(
                "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.oneplus.deskclock",
                "com.android.deskclock",
            ),
            listOf("clock"), fallbackAction = "android.intent.action.SHOW_ALARMS",
        ),
        AppAlias(
            "Files", listOf("files", "file manager", "my files", "files by google", "फ़ाइलें", "फाइल्स", "फ़ाइल मैनेजर"),
            listOf(
                "com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer",
                "com.google.android.documentsui",
            ),
            listOf("files", "my files", "file manager"),
        ),
    )

    private val byName: Map<String, AppAlias> =
        all.flatMap { alias -> alias.names.map { AppMatcher.normalize(it) to alias } }.toMap()

    /** Also tries the Latin spelling of a Devanagari name not listed ("इंस्टा" → "insta"). */
    fun forName(name: String): AppAlias? =
        byName[AppMatcher.normalize(name)] ?: Devanagari.toLatin(name)?.let { byName[AppMatcher.normalize(it)] }
}

/** Picks the installed app a spoken name most likely refers to. */
internal object AppMatcher {

    /** Lowercase letters, digits and combining marks only, so "Whats-App" == "whatsapp". */
    fun normalize(text: String): String =
        NON_WORD.replace(Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT), "")

    /**
     * Order: exact label, well-known alias, then label prefix, containment and small edit distance
     * (≤ 1 for 4–5 characters, ≤ 2 from 6). Shorter labels win ties, since they are closer to what was said.
     * A name heard in Devanagari that matches no label or alias is compared by its Latin spelling
     * ("टेलीग्राम" → "teligram" ≈ "Telegram"), since speech-to-text writes English names in Hindi script.
     */
    fun match(name: String, apps: List<LauncherApp>): LauncherApp? {
        val heard = normalize(name)
        if (heard.isEmpty()) return null
        val labelled = apps.map { it to normalize(it.label) }.filter { it.second.isNotEmpty() }

        apps.firstOrNull { it.label.trim().equals(name.trim(), ignoreCase = true) }?.let { return it }
        labelled.firstOrNull { it.second == heard }?.let { return it.first }
        AppAliases.forName(name)?.let { alias ->
            alias.packages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }?.let { return it }
            alias.labels.firstNotNullOfOrNull { label -> labelled.firstOrNull { it.second == normalize(label) } }
                ?.let { return it.first }
        }
        // Fuzzy tiers compare Latin spellings, so a name heard in Devanagari can match a Latin label.
        val query = latin(name)
        val labels = apps.map { it to latin(it.label) }.filter { it.second.isNotEmpty() }
        labels.firstOrNull { it.second == query }?.let { return it.first }
        if (query.length < 3) return null
        labels.filter { it.second.startsWith(query) }.minByOrNull { it.second.length }?.let { return it.first }
        labels.filter { it.second.length >= 4 && query.startsWith(it.second) }.maxByOrNull { it.second.length }
            ?.let { return it.first }
        labels.filter { it.second.contains(query) }.minByOrNull { it.second.length }?.let { return it.first }
        val maxDistance = when {
            query.length >= 6 -> 2
            query.length >= 4 -> 1
            else -> return null
        }
        return labels.map { it to editDistance(query, it.second) }
            .filter { it.second <= maxDistance }
            .minWithOrNull(compareBy({ it.second }, { it.first.second.length }))
            ?.first?.first
    }

    private fun latin(text: String): String = normalize(Devanagari.toLatin(text) ?: text)

    fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
}

/** Rough Latin spelling of Hindi-script words, for matching them against launcher labels. */
internal object Devanagari {
    /**
     * "इंस्टाग्राम" → "instagram", "स्नैपचैट" → "snaipchait"; other characters pass through, lowercased.
     * Inherent vowels are dropped at word ends and between vowelled syllables, as Hindi speakers
     * do. Null when [text] has no Devanagari.
     */
    fun toLatin(text: String): String? {
        if (text.none { it in BLOCK }) return null
        return Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .split(WHITESPACE).joinToString(" ") { word(it) }
    }

    /** A consonant (or none, for a lone vowel) and its vowel: "" after a virama; [inherent] if unwritten. */
    private class Syllable(val consonant: String, var vowel: String, val inherent: Boolean = false) {
        var coda = ""
    }

    private fun word(text: String): String {
        val syllables = mutableListOf<Syllable>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c in CONSONANTS -> {
                    var consonant = CONSONANTS.getValue(c)
                    if (next == NUKTA) {
                        consonant = NUKTA_CONSONANTS[c] ?: consonant
                        i++
                    }
                    val sign = text.getOrNull(i + 1)
                    syllables += when {
                        sign == VIRAMA -> Syllable(consonant, "").also { i++ }
                        sign != null && sign in VOWEL_SIGNS -> Syllable(consonant, VOWEL_SIGNS.getValue(sign)).also { i++ }
                        else -> Syllable(consonant, "a", inherent = true)
                    }
                }
                c in VOWELS -> syllables += Syllable("", VOWELS.getValue(c))
                c in VOWEL_SIGNS -> syllables += Syllable("", VOWEL_SIGNS.getValue(c))
                c == ANUSVARA || c == CHANDRABINDU -> {
                    val coda = if (next != null && next in LABIALS) "m" else "n"
                    syllables.lastOrNull()?.let { it.coda += coda } ?: run { syllables += Syllable(coda, "") }
                }
                c == VISARGA -> syllables.lastOrNull()?.let { it.coda += "h" }
                c in BLOCK -> Unit
                else -> syllables += Syllable(c.toString(), "")
            }
            i++
        }
        val last = syllables.lastIndex
        syllables.forEachIndexed { index, syllable ->
            if (!syllable.inherent) return@forEachIndexed
            val silent = when (index) {
                0 -> false
                last -> true
                // Between vowels, also across a following cluster: "नेटफ्लिक्स" is "netfliks".
                else -> syllable.coda.isEmpty() && syllables[index - 1].vowel.isNotEmpty() &&
                    (index + 1..last).firstOrNull { syllables[it].vowel.isNotEmpty() }
                        ?.let { next -> !(next == last && syllables[next].inherent) } == true
            }
            if (silent) syllable.vowel = ""
        }
        return syllables.joinToString("") { it.consonant + it.vowel + it.coda }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val VIRAMA = '\u094D'
    private const val NUKTA = '\u093C'
    private const val ANUSVARA = '\u0902'
    private const val CHANDRABINDU = '\u0901'
    private const val VISARGA = '\u0903'
    private val BLOCK = '\u0900'..'\u097F'
    private val LABIALS = setOf('प', 'फ', 'ब', 'भ', 'म')

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j",
        'झ' to "jh", 'ञ' to "n", 'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
        'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh",
        'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh",
        'स' to "s", 'ह' to "h",
    )
    private val NUKTA_CONSONANTS = mapOf('क' to "k", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri", 'ऍ' to "e", 'ए' to "e",
        'ऐ' to "ai", 'ऑ' to "o", 'ओ' to "o", 'औ' to "au",
    )
    private val VOWEL_SIGNS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri", 'ॅ' to "e", 'े' to "e", 'ै' to "ai",
        'ॉ' to "o", 'ो' to "o", 'ौ' to "au",
    )
}
