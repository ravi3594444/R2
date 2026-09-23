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
    fun editDistance() {
        assertEquals(0, AppMatcher.editDistance("maps", "maps"))
        assertEquals(1, AppMatcher.editDistance("maps", "map"))
        assertEquals(2, AppMatcher.editDistance("instagarm", "instagram"))
    }
}
