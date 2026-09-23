package ai.wakey.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningStepTest {
    @Test
    fun findsTheAppToOpenFirst() {
        assertEquals("Instagram", OpeningStep.appToOpen("open Instagram and search for cats"))
        assertEquals("Chrome", OpeningStep.appToOpen("Open Chrome and search for cats."))
        assertEquals("Settings", OpeningStep.appToOpen("open Settings and find Bluetooth"))
        assertEquals("YouTube", OpeningStep.appToOpen("please launch the YouTube app, then play lofi music"))
        assertEquals("Play Store", OpeningStep.appToOpen("open Play Store and search WhatsApp"))
        assertEquals("YouTube", OpeningStep.appToOpen("YouTube kholo aur songs chalao"))
    }

    @Test
    fun ignoresRequestsThatDontStartByOpeningAnApp() {
        assertNull(OpeningStep.appToOpen("open Calculator"))
        assertNull(OpeningStep.appToOpen("search for cats in Chrome"))
        assertNull(OpeningStep.appToOpen("open the email from Priya Sharma today and reply"))
        assertNull(OpeningStep.appToOpen("what's the weather and time"))
    }
}
