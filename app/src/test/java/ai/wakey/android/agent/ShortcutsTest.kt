package ai.wakey.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutsTest {
    private fun link(goal: String) = Shortcuts.forGoal(goal)?.link ?: error("no shortcut for \"$goal\"")

    @Test
    fun youTubeSearchesAreOneLink() {
        for (goal in listOf(
            "open YouTube and search for lofi music", "Open YouTube, then search lofi music.", "search for lofi music on YouTube",
            "look up lofi music on youtube", "YouTube kholo aur lofi music search karo", "YouTube pe lofi music dhundo",
            "यूट्यूब पर lofi music सर्च करो", "यूट्यूब खोलो और lofi music ढूंढो",
        )) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "https://www.youtube.com/results?search_query=lofi%20music", shortcut.link.uri)
            assertEquals(goal, "com.google.android.youtube", shortcut.link.packageName)
            assertEquals(goal, ShortcutDone.Results("lofi music"), shortcut.done)
            assertEquals(goal, listOf("lofi music"), shortcut.visible)
        }
    }

    @Test
    fun playingOnYouTubeOpensTheResultsAndGoesOn() {
        for (goal in listOf("play despacito on YouTube", "open YouTube and play despacito", "YouTube pe despacito chalao", "यूट्यूब पर despacito चलाओ")) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "https://www.youtube.com/results?search_query=despacito", shortcut.link.uri)
            assertNull(goal, shortcut.done)
        }
        assertEquals("YouTube results for “despacito”", link("play despacito on YouTube").target)
    }

    @Test
    fun webSearchesGoToGoogleInAnyBrowser() {
        for (goal in listOf(
            "search for pizza near me", "google pizza near me", "look up pizza near me", "open Chrome and search for pizza near me",
            "search pizza near me on google", "pizza near me search karo", "search for pizza near me in the browser",
        )) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "https://www.google.com/search?q=pizza%20near%20me", shortcut.link.uri)
            assertNull(goal, shortcut.link.packageName)
            assertEquals(goal, ShortcutDone.Results("pizza near me"), shortcut.done)
        }
    }

    @Test
    fun searchesInsideOtherAppsAreLeftToTheAgent() {
        for (goal in listOf(
            "search for cats on Instagram", "open Instagram and search for cats", "search for Priya in WhatsApp",
            "search for bluetooth in settings", "settings mein bluetooth search karo", "find the email from Priya",
            "show me the weather", "instagram pe cats search karo",
        )) {
            assertNull(goal, Shortcuts.forGoal(goal))
        }
    }

    @Test
    fun mapsSearchesAndNavigation() {
        assertEquals("geo:0,0?q=pizza%20near%20me", link("search for pizza near me on maps").uri)
        assertEquals("geo:0,0?q=the%20airport", link("open Google Maps and find the airport").uri)
        assertEquals(ShortcutDone.Results("the airport"), Shortcuts.forGoal("open Google Maps and find the airport")?.done)
        for (goal in listOf("navigate to the airport", "take me to the airport", "directions to the airport on maps", "the airport ka rasta batao")) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "google.navigation:q=the%20airport", shortcut.link.uri)
            assertEquals(goal, ShortcutDone.Navigation("the airport"), shortcut.done)
            assertEquals(goal, "com.google.android.apps.maps", shortcut.link.packageName)
        }
    }

    @Test
    fun playStoreAndSpotify() {
        for (goal in listOf("install WhatsApp", "install the WhatsApp app", "search for WhatsApp on the Play Store", "open Play Store and search WhatsApp")) {
            assertEquals(goal, "market://search?q=WhatsApp", link(goal).uri)
            assertEquals(goal, "com.android.vending", link(goal).packageName)
        }
        val play = Shortcuts.forGoal("play Believer on Spotify") ?: error("spotify")
        assertEquals("spotify:search:Believer", play.link.uri)
        assertEquals("com.spotify.music", play.link.packageName)
        assertNull(play.done)
    }

    @Test
    fun settingsPagesOpenDirectly() {
        for (goal in listOf(
            "turn on bluetooth", "turn bluetooth on", "switch off bluetooth", "enable Bluetooth", "open Settings and turn on Bluetooth",
            "bluetooth on karo", "bluetooth band kar do", "ब्लूटूथ चालू करो", "connect to my bluetooth headphones",
        )) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "android.settings.BLUETOOTH_SETTINGS", shortcut.link.action)
            assertEquals(goal, "com.android.settings", shortcut.link.packageName)
            assertEquals(goal, "Bluetooth settings", shortcut.link.target)
            // Something still has to be flipped on that page.
            assertNull(goal, shortcut.done)
        }
        assertEquals("android.settings.WIFI_SETTINGS", link("turn on wifi").action)
        assertEquals("android.settings.WIFI_SETTINGS", link("Wi-Fi on karo").action)
        assertEquals("android.settings.DISPLAY_SETTINGS", link("increase the brightness").action)
        assertEquals("android.settings.DISPLAY_SETTINGS", link("turn on dark mode").action)
        assertEquals("android.settings.DISPLAY_SETTINGS", link("brightness kam karo").action)
        assertEquals("android.settings.SOUND_SETTINGS", link("change my ringtone").action)
        assertEquals("android.settings.AIRPLANE_MODE_SETTINGS", link("turn on airplane mode").action)
        assertEquals("android.settings.LOCATION_SOURCE_SETTINGS", link("turn off location").action)
        assertEquals("android.settings.DATA_ROAMING_SETTINGS", link("turn on mobile data").action)
        assertEquals("android.settings.WIRELESS_SETTINGS", link("turn on hotspot").action)
        assertEquals("android.settings.BATTERY_SAVER_SETTINGS", link("turn on battery saver").action)
    }

    @Test
    fun openingASettingsPageIsDoneWhenItShows() {
        for (goal in listOf("open bluetooth settings", "show me the bluetooth settings", "go to Bluetooth", "bluetooth settings kholo", "open Settings and go to Bluetooth")) {
            val shortcut = Shortcuts.forGoal(goal) ?: error(goal)
            assertEquals(goal, "android.settings.BLUETOOTH_SETTINGS", shortcut.link.action)
            assertEquals(goal, ShortcutDone.Page("Bluetooth"), shortcut.done)
            assertEquals(goal, listOf("Bluetooth"), shortcut.visible)
        }
        assertEquals(ShortcutDone.Page("Battery"), Shortcuts.forGoal("check the battery")?.done)
        assertEquals(ShortcutDone.Page("About phone"), Shortcuts.forGoal("show me about phone")?.done)
        assertEquals(ShortcutDone.Page("Wi-Fi"), Shortcuts.forGoal("open wifi settings")?.done)
        assertEquals(listOf("wi-fi", "wi‑fi", "wifi", "internet"), Shortcuts.forGoal("open wifi settings")?.visible)
        // More to do after the page: not done yet.
        assertNull(Shortcuts.forGoal("open bluetooth settings and pair my headphones")?.done)
        assertEquals("android.settings.BLUETOOTH_SETTINGS", link("open bluetooth settings and pair my headphones").action)
    }

    @Test
    fun wordsThatOnlySoundLikeSettingsAreNotPages() {
        for (goal in listOf(
            "show me the time", "check the time", "show my location", "find my location", "open notifications", "check notifications",
            "show me my apps", "open YouTube", "turn on the light", "turn on the TV", "what's the weather", "call mum",
            "send a message to Priya", "open Settings", "show me the volume", "set an alarm for 6", "open the camera",
        )) {
            assertNull(goal, Shortcuts.forGoal(goal))
        }
    }

    @Test
    fun namedPagesStillOpenWhenAskedForAsSettings() {
        assertEquals("android.settings.LOCATION_SOURCE_SETTINGS", link("open location settings").action)
        assertEquals("android.settings.NOTIFICATION_SETTINGS", link("open notification settings").action)
        assertEquals("android.settings.SOUND_SETTINGS", link("open sound settings").action)
        assertEquals("android.settings.DATE_SETTINGS", link("open date and time settings").action)
        assertEquals("android.settings.APPLICATION_SETTINGS", link("open apps settings").action)
    }

    @Test
    fun queriesAreTrimmedAndBounded() {
        assertEquals("https://www.youtube.com/results?search_query=lofi", link("search for \"lofi\" on YouTube").uri)
        assertEquals("https://www.google.com/search?q=%E0%A4%AE%E0%A5%8C%E0%A4%B8%E0%A4%AE", link("मौसम सर्च करो").uri)
        assertNull(Shortcuts.forGoal("search for " + "word ".repeat(12).trim() + " on YouTube"))
        assertNull(Shortcuts.forGoal("search for on YouTube"))
    }
}
