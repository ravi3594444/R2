package ai.wakey.android.agent

import java.net.URLEncoder

/**
 * A deep link into an app, described without Android types so the parsing is testable: the
 * screen it opens would otherwise take the agent several taps and model calls to reach.
 */
internal data class AppLink(
    /** The app, for the model and the step list: "YouTube", "Settings". */
    val label: String,
    /** Where it goes, spoken and shown: "YouTube results for “lofi”", "Bluetooth settings". */
    val target: String,
    /** The package that should come to the front, or null when any app may answer (a web search). */
    val packageName: String?,
    val action: String,
    val uri: String? = null,
)

/** How a shortcut's request ends once its screen is up, when nothing more was asked. */
internal sealed interface ShortcutDone {
    data class Results(val query: String) : ShortcutDone
    data class Page(val title: String) : ShortcutDone
    data class Navigation(val place: String) : ShortcutDone
}

/**
 * [done] is null when the request goes on from the link's screen (play the first result, flip
 * the switch). [visible]: words expected on the screen when it is the right one.
 */
internal data class Shortcut(val link: AppLink, val done: ShortcutDone?, val visible: List<String>)

/**
 * Requests whose first screen is one intent away: search results in YouTube, Google, Maps, the
 * Play Store or Spotify, and Settings pages. "Open YouTube and search for lofi" then needs no
 * model call at all; "turn on Bluetooth" starts on the Bluetooth page instead of Settings' front
 * page. Anything else (or any app not listed) goes through the normal open-then-look loop.
 */
internal object Shortcuts {

    fun forGoal(goal: String): Shortcut? {
        val text = goal.trim().trimEnd('.', '!', '?', '।').replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        return youTube(text) ?: maps(text) ?: playStore(text) ?: spotify(text) ?: webSearch(text) ?: settings(text)
    }

    // ------------------------------------------------------------------ apps with a search screen

    private fun youTube(text: String): Shortcut? {
        val (query, plays) = query(text, YOUTUBE) ?: return null
        val link = AppLink(
            "YouTube", "YouTube results for “$query”", YOUTUBE_PACKAGE, VIEW,
            "https://www.youtube.com/results?search_query=${encode(query)}",
        )
        return Shortcut(link, if (plays) null else ShortcutDone.Results(query), listOf(query))
    }

    private fun spotify(text: String): Shortcut? {
        val (query, plays) = query(text, SPOTIFY) ?: return null
        val link = AppLink("Spotify", "Spotify results for “$query”", SPOTIFY_PACKAGE, VIEW, "spotify:search:${encode(query)}")
        return Shortcut(link, if (plays) null else ShortcutDone.Results(query), listOf(query))
    }

    private fun playStore(text: String): Shortcut? {
        val query = query(text, PLAY_STORE)?.first
            ?: INSTALL.matchEntire(text)?.groupValues?.get(1)?.trim()
            ?: return null
        val link = AppLink("Play Store", "Play Store results for “$query”", PLAY_STORE_PACKAGE, VIEW, "market://search?q=${encode(query)}")
        return Shortcut(link, ShortcutDone.Results(query), listOf(query))
    }

    private fun maps(text: String): Shortcut? {
        destination(text)?.let { place ->
            val link = AppLink("Maps", "navigation to $place", MAPS_PACKAGE, VIEW, "google.navigation:q=${encode(place)}")
            return Shortcut(link, ShortcutDone.Navigation(place), emptyList())
        }
        val place = query(text, MAPS)?.first ?: return null
        val link = AppLink("Maps", "Maps results for “$place”", MAPS_PACKAGE, VIEW, "geo:0,0?q=${encode(place)}")
        return Shortcut(link, ShortcutDone.Results(place), listOf(place))
    }

    /** "navigate to the airport", "take me home", "office ka rasta batao": where to, or null. */
    private fun destination(text: String): String? {
        val match = NAVIGATE.matchEntire(text) ?: return null
        val place = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: return null
        return place.takeIf { it.split(' ').size <= MAX_QUERY_WORDS }
    }

    private fun webSearch(text: String): Shortcut? {
        val query = query(text, WEB)?.first ?: return null
        // "search for cats on Instagram": that app's own search, which the agent operates on screen.
        if (OTHER_APP_TAIL.containsMatchIn(query) || OTHER_APP_HEAD.containsMatchIn(query)) return null
        val link = AppLink("Google", "Google results for “$query”", null, VIEW, "https://www.google.com/search?q=${encode(query)}")
        return Shortcut(link, ShortcutDone.Results(query), listOf(query))
    }

    /** The query and whether the request plays it (rather than just showing results), or null. */
    private fun query(text: String, app: SearchApp): Pair<String, Boolean>? {
        for (pattern in app.patterns + app.bare) {
            val match = pattern.matchEntire(text) ?: continue
            val verb = match.groups["verb"]?.value?.lowercase().orEmpty()
            val query = match.groups["q"]?.value?.trim()?.trim('"', '“', '”', '\'').orEmpty()
            if (query.isEmpty() || query.length > MAX_QUERY || query.split(' ').size > MAX_QUERY_WORDS) return null
            return query to PLAY_VERBS.containsMatchIn(verb)
        }
        return null
    }

    private class SearchApp(
        names: String,
        verbs: String,
        hinglishVerbs: String,
        devanagariNames: String,
        devanagariVerbs: String,
        /** Patterns that don't name the app: only the web search has them ("search for cats"). */
        val bare: List<Regex> = emptyList(),
    ) {
        val patterns = listOf(
            // "open YouTube and search for lofi", "YouTube, play lofi"
            Regex("""^(?:$OPEN)?(?:$names)(?:\s+app)?$CONNECTOR(?<verb>$verbs)\s+(?<q>.+)$""", RegexOption.IGNORE_CASE),
            // "search for lofi on YouTube", "play lofi in YouTube"
            Regex("""^(?<verb>$verbs)\s+(?<q>.+?)\s+(?:on|in|using|via)\s+(?:the\s+)?(?:$names)(?:\s+app)?$""", RegexOption.IGNORE_CASE),
            // "YouTube kholo aur lofi search karo", "YouTube pe lofi chalao"
            Regex("""^(?:$names)(?:\s+app)?\s+(?:kholo|khol do|open karo)$CONNECTOR(?<q>.+?)\s+(?<verb>$hinglishVerbs)$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:$names)\s+(?:pe|par|mein|me|main)\s+(?<q>.+?)\s+(?<verb>$hinglishVerbs)$""", RegexOption.IGNORE_CASE),
            // "यूट्यूब पर लोफी चलाओ", "यूट्यूब खोलो और लोफी सर्च करो"
            Regex("""^(?:$devanagariNames)\s+(?:पर|पे|में)\s+(?<q>.+?)\s+(?<verb>$devanagariVerbs)$"""),
            Regex("""^(?:$devanagariNames)\s+खोलो(?:\s*,)?\s+(?:और|फिर)\s+(?<q>.+?)\s+(?<verb>$devanagariVerbs)$"""),
        )
    }

    private const val OPEN = """(?:please\s+)?(?:open|launch|start|go to)\s+(?:the\s+)?"""
    private const val CONNECTOR = """(?:\s*,\s*|\s+)(?:and then|and|then|aur|phir|&)\s+"""
    private const val SEARCH = """search(?:\s+for)?+|look\s+up|look\s+for|find|show(?:\s+me)?+"""
    private const val PLAY = """play|put\s+on|listen\s+to|watch"""
    private const val HINGLISH_SEARCH = """search\s+karo|search\s+kar\s+do|dhundo|dhoondo|dikhao|khojo"""
    private const val HINGLISH_PLAY = """chalao|chala\s+do|play\s+karo|play\s+kar\s+do|lagao|laga\s+do|suno|sunao"""
    private const val DEVANAGARI_SEARCH = """सर्च करो|ढूंढो|ढूँढो|दिखाओ|खोजो"""
    private const val DEVANAGARI_PLAY = """चलाओ|चला दो|प्ले करो|लगाओ|लगा दो|सुनाओ"""

    private val PLAY_VERBS = Regex("""^(?:$PLAY|$HINGLISH_PLAY|$DEVANAGARI_PLAY)$""", RegexOption.IGNORE_CASE)

    private val YOUTUBE = SearchApp(
        names = "you\\s?tube", verbs = "$SEARCH|$PLAY", hinglishVerbs = "$HINGLISH_SEARCH|$HINGLISH_PLAY",
        devanagariNames = "यूट्यूब|यू ट्यूब|यूटूब", devanagariVerbs = "$DEVANAGARI_SEARCH|$DEVANAGARI_PLAY",
    )
    private val SPOTIFY = SearchApp(
        names = "spotify", verbs = "$SEARCH|$PLAY", hinglishVerbs = "$HINGLISH_SEARCH|$HINGLISH_PLAY",
        devanagariNames = "स्पॉटिफाई|स्पोटिफाई", devanagariVerbs = "$DEVANAGARI_SEARCH|$DEVANAGARI_PLAY",
    )
    private val PLAY_STORE = SearchApp(
        names = "play\\s?store|google\\s+play(?:\\s+store)?", verbs = "$SEARCH|install|download|get",
        hinglishVerbs = "$HINGLISH_SEARCH|install\\s+karo|download\\s+karo",
        devanagariNames = "प्ले स्टोर|प्लेस्टोर", devanagariVerbs = "$DEVANAGARI_SEARCH|इंस्टॉल करो|डाउनलोड करो",
    )
    private val MAPS = SearchApp(
        names = "google\\s+maps|maps", verbs = "$SEARCH|locate|where is",
        hinglishVerbs = "$HINGLISH_SEARCH|batao", devanagariNames = "गूगल मैप्स|मैप्स|मैप", devanagariVerbs = "$DEVANAGARI_SEARCH|बताओ",
    )
    private val WEB = SearchApp(
        names = "google|google\\s+chrome|chrome|the\\s+browser|browser|the\\s+web|the\\s+internet|internet",
        verbs = "$SEARCH|google", hinglishVerbs = "$HINGLISH_SEARCH|google\\s+karo",
        devanagariNames = "गूगल|क्रोम|इंटरनेट", devanagariVerbs = "$DEVANAGARI_SEARCH|गूगल करो",
        bare = listOf(
            // "search for cats", "google the weather", "look up the capital of France"
            Regex("""^(?:please\s+)?(?<verb>search(?:\s+for)?+|google|look\s+up)\s+(?<q>.+)$""", RegexOption.IGNORE_CASE),
            // "cats search karo", "मौसम सर्च करो"
            Regex("""^(?<q>.+?)\s+(?<verb>search\s+karo|search\s+kar\s+do|google\s+karo|सर्च\s+करो|गूगल\s+करो)$""", RegexOption.IGNORE_CASE),
        ),
    )

    /** "install WhatsApp", "install the Zomato app". */
    private val INSTALL = Regex("""^(?:please\s+)?install\s+(?:the\s+)?(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE)

    private val NAVIGATE = Regex(
        """^(?:please\s+)?(?:navigate\s+to|navigate\s+me\s+to|directions\s+to|get\s+directions\s+to|take\s+me\s+to|take\s+me|""" +
            """route\s+to|drive\s+to|start\s+navigation\s+to|start\s+navigating\s+to)\s+(.+?)(?:\s+(?:on|in|using)\s+(?:google\s+)?maps)?$|""" +
            """^(.+?)\s+(?:ka|ki|ke)\s+(?:rasta|raasta|route)\s+(?:batao|dikhao)$""",
        RegexOption.IGNORE_CASE,
    )

    private const val OTHER_APPS =
        "you\\s?tube|instagram|insta|facebook|whatsapp|telegram|snapchat|twitter|x|spotify|amazon|flipkart|" +
            "play\\s?store|google\\s+play|maps|google\\s+maps|settings|gmail|netflix|prime\\s+video|hotstar|zomato|" +
            "swiggy|myntra|meesho|linkedin|reddit|pinterest|contacts|phone|messages|photos|gallery|files|drive"

    private val OTHER_APP_TAIL = Regex("""(?:^|\s+)(?:on|in|from|using)\s+(?:the\s+|my\s+)?(?:$OTHER_APPS)(?:\s+app)?$""", RegexOption.IGNORE_CASE)
    private val OTHER_APP_HEAD = Regex("""^(?:$OTHER_APPS)\s+(?:pe|par|mein|me|main)\b""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ Settings pages

    private fun settings(text: String): Shortcut? {
        // Whole first: "date and time" is one page, not two clauses.
        settingsPage(text, more = false)?.let { return it }
        val clauses = text.split(CONNECTOR_REGEX)
        if (clauses.size < 2) return null
        // "open Settings and turn on Bluetooth": the second clause names the page.
        val at = if (SETTINGS_APP.matches(clauses[0])) 1 else 0
        return settingsPage(clauses[at], more = clauses.size > at + 1)
    }

    /** [clause] names a Settings page to change something on or to look at; [more] follows it. */
    private fun settingsPage(clause: String, more: Boolean): Shortcut? {
        val (topic, opensOnly) = SETTINGS_PATTERNS.firstNotNullOfOrNull { (pattern, opens) ->
            pattern.matchEntire(clause)?.groups?.get("topic")?.value?.trim()?.let { it to opens }
        } ?: return null
        val page = PAGES.firstOrNull { it.matches(topic, opensOnly) } ?: return null
        val link = AppLink("Settings", "${page.title} settings", SETTINGS_PACKAGE, page.action)
        val done = if (opensOnly && !more) ShortcutDone.Page(page.title) else null
        return Shortcut(link, done, page.visible)
    }

    /**
     * [words] name the page; [visible] are words expected on it. A page that "open …" alone would
     * misread ("show my location" wants a map, not the Location switch) sets [opensByName] false:
     * it is then reached only by a toggle verb or with a "settings" suffix.
     */
    private class SettingsPage(
        val title: String,
        val action: String,
        words: String,
        val visible: List<String> = listOf(title),
        private val opensByName: Boolean = true,
    ) {
        private val whole = Regex("""^(?:the\s+|my\s+|phone'?s\s+|phone\s+|mera\s+|meri\s+)?(?:$words)(?:\s+(?:settings?|options?|page|mode))?$""", RegexOption.IGNORE_CASE)
        private val named = Regex("""^(?:the\s+|my\s+|phone'?s\s+|phone\s+)?(?:$words)\s+(?:settings?|options?|page)$""", RegexOption.IGNORE_CASE)
        private val inside = Regex("""(?<![\p{L}\p{M}])(?:$words)(?![\p{L}\p{M}])""", RegexOption.IGNORE_CASE)

        /**
         * The topic is the page, or (when changing something) a short phrase about it: "bluetooth
         * headphones", "screen brightness".
         */
        fun matches(topic: String, opensOnly: Boolean): Boolean = when {
            opensOnly && !opensByName -> named.matches(topic)
            opensOnly -> whole.matches(topic)
            else -> whole.matches(topic) || (topic.split(' ').size <= MAX_TOPIC_WORDS && inside.containsMatchIn(topic))
        }
    }

    private val PAGES = listOf(
        SettingsPage("Bluetooth", "android.settings.BLUETOOTH_SETTINGS", "bluetooth|blue\\s?tooth|ब्लूटूथ|ब्लू टूथ"),
        SettingsPage("Wi-Fi", "android.settings.WIFI_SETTINGS", "wi-?fi|wi\\s+fi|wlan|वाई-?फाई|वाई फाई|वाईफ़ाई", listOf("wi-fi", "wi‑fi", "wifi", "internet")),
        SettingsPage("Hotspot", "android.settings.WIRELESS_SETTINGS", "hotspot|hot\\s+spot|tethering|हॉटस्पॉट", listOf("hotspot", "tethering", "network", "internet")),
        SettingsPage("Mobile network", "android.settings.DATA_ROAMING_SETTINGS", "mobile\\s+data|cellular\\s+data|cellular|sims?|sim\\s+cards?|roaming|mobile\\s+network|network\\s+mode|5g|4g|lte|volte|मोबाइल डेटा|मोबाइल डाटा|सिम", listOf("mobile", "sim", "network", "data")),
        SettingsPage("Data usage", "android.settings.DATA_USAGE_SETTINGS", "data\\s+usage|data\\s+limit|data\\s+saver|data\\s+warning", listOf("data")),
        SettingsPage("Airplane mode", "android.settings.AIRPLANE_MODE_SETTINGS", "airplane(?:\\s+mode)?|aeroplane(?:\\s+mode)?|flight\\s+mode|एयरप्लेन मोड|फ्लाइट मोड|हवाई जहाज मोड", listOf("airplane", "aeroplane", "flight")),
        SettingsPage("Location", "android.settings.LOCATION_SOURCE_SETTINGS", "location(?:\\s+services?)?|gps|लोकेशन", opensByName = false),
        SettingsPage("NFC", "android.settings.NFC_SETTINGS", "nfc"),
        SettingsPage("Display", "android.settings.DISPLAY_SETTINGS", "display|(?:screen\\s+)?brightness|screen\\s+timeout|dark\\s+(?:mode|theme)|light\\s+theme|night\\s+light|font\\s+size|display\\s+size|refresh\\s+rate|auto[- ]?rotate|screen\\s+rotation|ब्राइटनेस|डिस्प्ले|डार्क मोड", listOf("display", "brightness", "dark")),
        SettingsPage("Sound", "android.settings.SOUND_SETTINGS", "sound|sounds|volume|ringtone|ring\\s+tone|vibrat\\w*|silent\\s+mode|media\\s+volume|ring\\s+volume|साउंड|आवाज़|आवाज|वॉल्यूम|रिंगटोन", listOf("sound", "volume", "ring"), opensByName = false),
        SettingsPage("Do not disturb", "android.settings.ZEN_MODE_SETTINGS", "do\\s+not\\s+disturb|dnd|डू नॉट डिस्टर्ब", listOf("disturb")),
        SettingsPage("Notifications", "android.settings.NOTIFICATION_SETTINGS", "notifications?|नोटिफिकेशन", listOf("notification"), opensByName = false),
        SettingsPage("Battery", "android.settings.BATTERY_SAVER_SETTINGS", "battery(?:\\s+saver)?|power\\s+sav\\w+|बैटरी"),
        SettingsPage("Storage", "android.settings.INTERNAL_STORAGE_SETTINGS", "storage|free\\s+up\\s+space|स्टोरेज"),
        SettingsPage("Date & time", "android.settings.DATE_SETTINGS", "date\\s*(?:and|&)\\s*time|time\\s*zone|timezone|date|time|clock|automatic\\s+(?:date|time)|तारीख|समय", listOf("date", "time"), opensByName = false),
        SettingsPage("Languages", "android.settings.LOCALE_SETTINGS", "languages?|locale|system\\s+language|भाषा", listOf("language")),
        SettingsPage("Keyboard", "android.settings.INPUT_METHOD_SETTINGS", "keyboards?|input\\s+method|कीबोर्ड", listOf("keyboard", "input")),
        SettingsPage("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS", "accessibility|talkback|एक्सेसिबिलिटी"),
        SettingsPage("Apps", "android.settings.APPLICATION_SETTINGS", "apps|installed\\s+apps|all\\s+apps|app\\s+info|applications?|manage\\s+apps|app\\s+management|ऐप्स", listOf("app"), opensByName = false),
        SettingsPage("Default apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "default\\s+apps?|default\\s+browser|default\\s+(?:phone|sms|messaging|dialer)\\s+app", listOf("default")),
        SettingsPage("Security", "android.settings.SECURITY_SETTINGS", "security|screen\\s+lock|fingerprint|face\\s+unlock|lock\\s+screen\\s+settings|सिक्योरिटी|फिंगरप्रिंट", listOf("security", "lock", "fingerprint", "face")),
        SettingsPage("Privacy", "android.settings.PRIVACY_SETTINGS", "privacy|permissions?\\s+manager|app\\s+permissions|प्राइवेसी", listOf("privacy", "permission")),
        SettingsPage("VPN", "android.settings.VPN_SETTINGS", "vpn"),
        SettingsPage("Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "developer\\s+options?|developer\\s+settings|developer\\s+mode|usb\\s+debugging", listOf("developer")),
        SettingsPage("About phone", "android.settings.DEVICE_INFO_SETTINGS", "about\\s+(?:phone|device|this\\s+phone)|android\\s+version|device\\s+info|phone\\s+info|imei|build\\s+number|software\\s+information", listOf("about", "android version", "device")),
    )

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private val CONNECTOR_REGEX = Regex(CONNECTOR, RegexOption.IGNORE_CASE)
    private val SETTINGS_APP = Regex("""^(?:$OPEN)?(?:settings|setting|सेटिंग्स|सेटिंग)(?:\s+app)?$|^(?:settings|सेटिंग्स)\s+(?:kholo|khol do|open karo|खोलो)$""", RegexOption.IGNORE_CASE)

    /** (pattern, opens only): a page to change something on, or just to look at. */
    private val SETTINGS_PATTERNS: List<Pair<Regex, Boolean>> = listOf(
        // "turn on Bluetooth", "increase the brightness", "change my ringtone"
        Regex(
            """^(?:please\s+)?(?:turn\s+(?:on|off|up|down)|switch\s+(?:on|off)|enable|disable|activate|deactivate|put\s+on|""" +
                """increase|decrease|raise|lower|change|set|adjust|toggle|start|stop|connect(?:\s+to)?|disconnect(?:\s+from)?|""" +
                """turn)\s+(?<topic>.+?)(?:\s+(?:on|off|up|down))?(?:\s+(?:for\s+me|please|now|abhi|jaldi))?$""",
            RegexOption.IGNORE_CASE,
        ) to false,
        // "open Bluetooth settings", "show me the battery", "go to display settings"
        Regex(
            """^(?:please\s+)?(?:open|go\s+to|show(?:\s+me)?|check|see|view|find|look\s+at)\s+(?<topic>.+?\s+(?:settings?|options?|page|menu|info|information))$""",
            RegexOption.IGNORE_CASE,
        ) to true,
        Regex("""^(?:please\s+)?(?:open|go\s+to|show(?:\s+me)?|check|see|view)\s+(?<topic>.+?)$""", RegexOption.IGNORE_CASE) to true,
        // "bluetooth on karo", "brightness kam karo", "wifi band kar do"
        Regex(
            """^(?<topic>.+?)\s+(?:on|off|band|bandh|chalu|chaalu|kam|zyada|jyada|tez|dheema|dheemi|badlo|badal|set|connect|disconnect)\s+""" +
                """(?:karo|kar\s+do|kardo|kar\s+de|kijiye|do|de)$""",
            RegexOption.IGNORE_CASE,
        ) to false,
        Regex("""^(?:settings\s+(?:mein|me|main)\s+)?(?<topic>.+?)\s+(?:kholo|khol\s+do|dikhao|open\s+karo|check\s+karo|dekho)$""", RegexOption.IGNORE_CASE) to true,
        // "ब्लूटूथ चालू करो", "ब्राइटनेस कम करो", "बैटरी सेटिंग्स खोलो"
        Regex("""^(?<topic>.+?)\s+(?:ऑन|ऑफ|चालू|बंद|कम|ज़्यादा|ज्यादा|तेज़|तेज|बदलो|बदल)\s+(?:करो|कर\s+दो|कीजिए|कीजिये|दो)$""") to false,
        Regex("""^(?<topic>.+?)\s+(?:खोलो|खोल\s+दो|दिखाओ|देखो)$""") to true,
    )

    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val VIEW = "android.intent.action.VIEW"
    private const val MAX_QUERY = 80
    private const val MAX_QUERY_WORDS = 10
    private const val MAX_TOPIC_WORDS = 4

    /** Percent-encodes for a query string; spaces as %20 so `geo:` and `spotify:` URIs read them too. */
    private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
