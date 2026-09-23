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
            "Settings", listOf("settings", "setting", "सेटिंग्स", "सेटिंग", "सेटिंगस"), listOf("com.android.settings"),
            listOf("settings"), fallbackAction = "android.settings.SETTINGS",
        ),
        AppAlias(
            "Calculator", listOf("calculator", "calc", "कैलकुलेटर", "कैल्कुलेटर"),
            listOf(
                "com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.miui.calculator",
                "com.oneplus.calculator", "com.coloros.calculator", "com.android.calculator2",
            ),
            listOf("calculator"), fallbackCategory = "android.intent.category.APP_CALCULATOR",
        ),
        AppAlias(
            "Camera", listOf("camera", "कैमरा"),
            listOf(
                "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.oneplus.camera",
                "com.oplus.camera", "com.android.camera", "com.android.camera2",
            ),
            listOf("camera"), fallbackAction = "android.media.action.STILL_IMAGE_CAMERA",
        ),
        AppAlias("Chrome", listOf("chrome", "google chrome", "क्रोम"), listOf("com.android.chrome"), listOf("chrome")),
        AppAlias("YouTube", listOf("youtube", "you tube", "यूट्यूब", "यू ट्यूब"), listOf("com.google.android.youtube"), listOf("youtube")),
        AppAlias(
            "WhatsApp", listOf("whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप"),
            listOf("com.whatsapp", "com.whatsapp.w4b"), listOf("whatsapp", "whatsapp business"),
        ),
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

    fun forName(name: String): AppAlias? = byName[AppMatcher.normalize(name)]
}

/** Picks the installed app a spoken name most likely refers to. */
internal object AppMatcher {

    /** Lowercase letters, digits and combining marks only, so "Whats-App" == "whatsapp". */
    fun normalize(text: String): String =
        NON_WORD.replace(Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT), "")

    /**
     * Order: exact label, well-known alias, then label prefix, containment and small edit distance
     * (≤ 1 for 4–5 characters, ≤ 2 from 6). Shorter labels win ties, since they are closer to what was said.
     */
    fun match(name: String, apps: List<LauncherApp>): LauncherApp? {
        val query = normalize(name)
        if (query.isEmpty()) return null
        val labelled = apps.map { it to normalize(it.label) }.filter { it.second.isNotEmpty() }

        apps.firstOrNull { it.label.trim().equals(name.trim(), ignoreCase = true) }?.let { return it }
        labelled.firstOrNull { it.second == query }?.let { return it.first }
        AppAliases.forName(name)?.let { alias ->
            alias.packages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }?.let { return it }
            alias.labels.firstNotNullOfOrNull { label -> labelled.firstOrNull { it.second == normalize(label) } }
                ?.let { return it.first }
        }
        if (query.length < 3) return null
        labelled.filter { it.second.startsWith(query) }.minByOrNull { it.second.length }?.let { return it.first }
        labelled.filter { it.second.length >= 4 && query.startsWith(it.second) }.maxByOrNull { it.second.length }
            ?.let { return it.first }
        labelled.filter { it.second.contains(query) }.minByOrNull { it.second.length }?.let { return it.first }
        val maxDistance = when {
            query.length >= 6 -> 2
            query.length >= 4 -> 1
            else -> return null
        }
        return labelled.map { it to editDistance(query, it.second) }
            .filter { it.second <= maxDistance }
            .minWithOrNull(compareBy({ it.second }, { it.first.second.length }))
            ?.first?.first
    }

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
