package ai.wakey.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastCommandRouterTest {

    private fun assertRoutes(expected: FastCommand?, vararg utterances: String) {
        for (utterance in utterances) assertEquals("\"$utterance\"", expected, FastCommandRouter.route(utterance))
    }

    @Test
    fun torchOnInEnglish() = assertRoutes(
        FastCommand.Torch(true),
        "turn on the flashlight", "Turn on flashlight.", "flashlight on", "torch on", "switch on the torch",
        "turn the torch on", "Hey Wakey, turn on the torch please", "can you please switch on the flashlight?",
        "turn on flash light", "enable the flashlight", "open torch",
    )

    @Test
    fun torchOffInEnglish() = assertRoutes(
        FastCommand.Torch(false),
        "turn off the flashlight", "turn off flashlight", "turn off the torch", "turn off torch", "torch off",
        "flashlight off!", "switch the torch off", "ok wakey turn off the flashlight now",
    )

    @Test
    fun torchInHinglish() {
        assertRoutes(FastCommand.Torch(true), "torch jalao", "flashlight on karo", "torch on kar do", "flashlight ko on karo", "torch chalu karo")
        assertRoutes(FastCommand.Torch(false), "torch band karo", "torch band kar do", "flashlight off karo", "torch bujhao")
    }

    @Test
    fun torchInDevanagari() {
        assertRoutes(FastCommand.Torch(true), "टॉर्च जलाओ", "टॉर्च चालू करो", "फ्लैशलाइट ऑन करो", "कृपया टॉर्च जला दो।", "टॉर्च को चालू कर दो")
        assertRoutes(FastCommand.Torch(false), "फ्लैशलाइट बंद करो", "टॉर्च बंद कर दो", "टॉर्च बुझाओ")
    }

    @Test
    fun precomposedAndZeroWidthDevanagariStillMatch() {
        // U+095E is the precomposed "फ़"; NFC turns it into फ + nukta.
        assertRoutes(FastCommand.Torch(false), "फ़्लैशलाइट बंद करो")
        assertRoutes(FastCommand.Torch(true), "टॉ‍र्च जलाओ")
    }

    @Test
    fun openAppKeepsTheSpokenName() {
        assertRoutes(FastCommand.OpenApp("YouTube"), "open YouTube", "Open YouTube.", "launch YouTube", "open the YouTube app")
        assertRoutes(FastCommand.OpenApp("calculator"), "start calculator", "please open the calculator app", "calculator kholo")
        assertRoutes(FastCommand.OpenApp("Settings"), "open Settings", "hey wakey, open Settings", "Settings khol do")
        assertRoutes(FastCommand.OpenApp("Play Store"), "open Play Store")
        assertRoutes(FastCommand.OpenApp("google play store"), "open google play store")
        assertRoutes(FastCommand.OpenApp("WhatsApp"), "can you open WhatsApp for me", "WhatsApp open karo", "open up WhatsApp")
    }

    @Test
    fun openAppInDevanagari() {
        assertRoutes(FastCommand.OpenApp("यूट्यूब"), "यूट्यूब खोलो", "यूट्यूब खोल दो")
        assertRoutes(FastCommand.OpenApp("कैलकुलेटर"), "कैलकुलेटर को खोलो", "कृपया कैलकुलेटर खोलिए")
    }

    @Test
    fun navigation() {
        assertRoutes(FastCommand.GoHome, "go home", "Home screen", "go to the home screen", "take me home", "होम स्क्रीन पर जाओ", "open home screen")
        assertRoutes(FastCommand.GoBack, "go back", "Go back.", "back", "wapas jao", "वापस जाओ", "please go back")
    }

    @Test
    fun multiStepRequestsAreNotFast() = assertRoutes(
        null,
        "open Chrome and search for cats", "open Settings and find Bluetooth", "open YouTube then play music",
        "turn on the flashlight and open camera", "open WhatsApp and send hi to Priya", "YouTube kholo aur gaana chalao",
        "टॉर्च जलाओ फिर यूट्यूब खोलो", "व्हाट्सएप खोलो और मैसेज भेजो", "open YouTube play despacito",
        "open WhatsApp to message mom", "open chrome in incognito", "start a timer",
    )

    @Test
    fun otherRequestsAreNotFast() = assertRoutes(
        null,
        "", "   ", "please", "open", "open the app", "search for cats", "call mom", "send a message to Priya",
        "turn on bluetooth", "what's the weather", "torch", "play despacito on youtube", "torch jalao aur camera kholo",
        "open this very long made up app name here",
    )
}
