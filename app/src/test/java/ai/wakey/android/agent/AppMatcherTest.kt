package ai.wakey.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        LauncherApp("Settings", "com.android.settings", ".Settings"),
        LauncherApp("Calculator Vault", "com.hider.vault", ".Main"),
        LauncherApp("Calculator", "com.sec.android.app.popupcalculator", ".Calculator"),
        LauncherApp("YouTube", "com.google.android.youtube", ".Home"),
        LauncherApp("YouTube Music", "com.google.android.apps.youtube.music", ".Music"),
        LauncherApp("WhatsApp", "com.whatsapp", ".Main"),
        LauncherApp("Chrome", "com.android.chrome", ".Main"),
        LauncherApp("Google Photos", "com.google.android.apps.photos", ".Home"),
        LauncherApp("Instagram", "com.instagram.android", ".Main"),
        LauncherApp("Maps", "com.google.android.apps.maps", ".Maps"),
        LauncherApp("Phone", "com.samsung.android.dialer", ".Dialer"),
    )

    private fun matchPackage(name: String) = AppMatcher.match(name, apps)?.packageName

    @Test
    fun exactAndNormalisedLabels() {
        assertEquals("com.google.android.youtube", matchPackage("YouTube"))
        assertEquals("com.google.android.youtube", matchPackage("you tube"))
        assertEquals("com.whatsapp", matchPackage("Whats-App"))
        assertEquals("com.sec.android.app.popupcalculator", matchPackage("calculator"))
    }

    @Test
    fun aliasesIncludingHindiNames() {
        assertEquals("com.sec.android.app.popupcalculator", matchPackage("कैलकुलेटर"))
        assertEquals("com.android.settings", matchPackage("सेटिंग्स"))
        assertEquals("com.google.android.youtube", matchPackage("यूट्यूब"))
        assertEquals("com.whatsapp", matchPackage("व्हाट्सएप"))
        assertEquals("com.google.android.apps.photos", matchPackage("gallery"))
        assertEquals("com.samsung.android.dialer", matchPackage("dialer"))
        assertEquals("com.android.chrome", matchPackage("google chrome"))
    }

    @Test
    fun fuzzyTiers() {
        assertEquals("prefix", "com.instagram.android", matchPackage("insta"))
        assertEquals("query starts with label", "com.android.chrome", matchPackage("chrome browser"))
        assertEquals("contains", "com.google.android.apps.youtube.music", matchPackage("music"))
        assertEquals("edit distance 1", "com.whatsapp", matchPackage("watsapp"))
        assertEquals("edit distance 2", "com.instagram.android", matchPackage("instagarm"))
        assertEquals("shorter label wins ties", "com.google.android.youtube", matchPackage("youtub"))
    }

    @Test
    fun unknownAppsDoNotMatch() {
        assertNull(matchPackage("Spotify"))
        assertNull(matchPackage("ab"))
        assertNull(matchPackage(""))
        assertNull(matchPackage("uber"))
    }

    @Test
    fun aliasFallbackIsAvailableWhenNothingIsInstalled() {
        assertNull(AppMatcher.match("calculator", emptyList()))
        assertEquals("android.intent.category.APP_CALCULATOR", AppAliases.forName("Calculator")?.fallbackCategory)
        assertEquals("android.settings.SETTINGS", AppAliases.forName("settings")?.fallbackAction)
        assertNull(AppAliases.forName("spotify"))
    }

    @Test
    fun devanagariAppNamesFromSpeech() {
        assertEquals("com.instagram.android", matchPackage("इंस्टाग्राम"))
        assertEquals("com.instagram.android", matchPackage("इंस्टा"))
        assertEquals("com.android.chrome", matchPackage("क्रोम"))
        assertEquals("com.android.settings", matchPackage("सेटिंग्स"))
        assertEquals("com.whatsapp", matchPackage("व्हाट्सअप"))
        assertEquals("com.google.android.youtube", matchPackage("यूटूब"))
        assertEquals("com.sec.android.app.popupcalculator", matchPackage("केलकुलेटर"))
        assertEquals("android.media.action.STILL_IMAGE_CAMERA", AppAliases.forName("कैमरा")?.fallbackAction)
        assertEquals("Instagram", AppAliases.forName("इन्स्टाग्राम")?.label)
    }

    @Test
    fun devanagariNamesMatchLatinLabelsBySpelling() {
        val more = listOf(
            LauncherApp("Telegram", "org.telegram.messenger", ".Main"),
            LauncherApp("Snapchat", "com.snapchat.android", ".Main"),
            LauncherApp("Flipkart", "com.flipkart.android", ".Main"),
            LauncherApp("Netflix", "com.netflix.mediaclient", ".Main"),
            LauncherApp("Spotify", "com.spotify.music", ".Main"),
            LauncherApp("Zomato", "com.application.zomato", ".Main"),
        )
        fun match(name: String) = AppMatcher.match(name, apps + more)?.label
        assertEquals("Telegram", match("टेलीग्राम"))
        assertEquals("Snapchat", match("स्नैपचैट"))
        assertEquals("Flipkart", match("फ्लिपकार्ट"))
        assertEquals("Netflix", match("नेटफ्लिक्स"))
        assertEquals("Spotify", match("स्पॉटिफाई"))
        assertEquals("Zomato", match("ज़ोमैटो"))
        assertEquals("Maps", match("मैप्स"))
        assertNull(match("उबर"))
    }

    @Test
    fun devanagariToLatin() {
        assertEquals("instagram", Devanagari.toLatin("इंस्टाग्राम"))
        assertEquals("teligram", Devanagari.toLatin("टेलीग्राम"))
        assertEquals("snaipchait", Devanagari.toLatin("स्नैपचैट"))
        assertEquals("flipkart", Devanagari.toLatin("फ्लिपकार्ट"))
        assertEquals("kailkuletar", Devanagari.toLatin("कैलकुलेटर"))
        assertEquals("zomaito", Devanagari.toLatin("ज़ोमैटो"))
        assertEquals("gugal mais", Devanagari.toLatin("गूगल मैस"))
        assertEquals("rang", Devanagari.toLatin("रंग"))
        assertEquals("kampyutar", Devanagari.toLatin("कंप्यूटर"))
        assertEquals("youtube kholo", Devanagari.toLatin("YouTube खोलो"))
        assertNull(Devanagari.toLatin("Settings"))
    }

    @Test
    fun editDistance() {
        assertEquals(0, AppMatcher.editDistance("maps", "maps"))
        assertEquals(1, AppMatcher.editDistance("maps", "map"))
        assertEquals(2, AppMatcher.editDistance("instagarm", "instagram"))
    }
}
