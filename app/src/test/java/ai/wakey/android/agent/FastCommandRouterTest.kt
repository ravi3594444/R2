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
    fun theFlashlightIsNotAnApp() {
        assertRoutes(
            FastCommand.Torch(true),
            "open the flashlight app", "open flashlight app", "launch the torch", "flashlight kholo", "torch khol do",
            "flashlight open karo", "torch app kholo", "flashlight wala app open karo", "light up the torch",
        )
        assertRoutes(FastCommand.Torch(false), "close the flashlight app", "flashlight close karo", "torch stop karo")
    }

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

    // Transcripts below were returned by Deepgram Flux (flux-general-multi, hints en+hi) for TTS clips
    // of Indian-English and Hinglish commands; see FluxCommandSetLiveTest.

    @Test
    fun leftoverOrMisheardWakeWordIsIgnored() {
        // AssistantController.stripWakePhrase removes "Hey," but stops at a misheard "Wakey".
        assertRoutes(
            FastCommand.Torch(true),
            "Becky. Turn on the flashlight.", "Hey, Becky. Turn on the flashlight.", "waking. Turn on the flashlight.",
            "Hey, Wiki. Turn on the flashlight.", "Vicky, turn on the flashlight", "Hey Wakey, torch jalao",
            "हे वेकी, टॉर्च जलाओ",
        )
        assertRoutes(FastCommand.OpenApp("YouTube"), "Hey Vicky, open YouTube", "Wacky open YouTube", "wakey YouTube kholo")
        assertRoutes(null, "Becky", "Vicky, call mom")
    }

    @Test
    fun mixedScriptHinglishFromFlux() {
        assertRoutes(FastCommand.Torch(true), "torch जलाओ.", "torch जलाऊ.", "तोड़ जलाओ", "तोड़ जलाओ.", "तोड़ चलाओ", "torch chalao")
        assertRoutes(
            FastCommand.Torch(false),
            "Clashlight band करो.", "Slashlight band करो.", "Flashlight band Karo.", "Flashlight ban Karo.",
            "flashlight bandh karo", "torch बंद कर दो",
        )
    }

    @Test
    fun misheardEnglishTorchCommands() {
        assertRoutes(FastCommand.Torch(false), "Turn off the dodge.", "Turn off a torch.", "turn of the torch", "switch of the flashlight")
        assertRoutes(FastCommand.Torch(true), "torch light on", "turn on the torch light", "phone ki torch jalao")
    }

    @Test
    fun misheardHinglishVerbs() {
        assertRoutes(FastCommand.OpenApp("YouTube"), "YouTube Colo.", "YouTube Colo", "YouTube kolo", "YouTube holo")
        assertRoutes(FastCommand.OpenApp("Calculator"), "Calculator open Caro", "Calculator kholo please")
        assertRoutes(FastCommand.OpenApp("WhatsApp"), "WhatsApp open Caro.", "WhatsApp open kar do yaar")
        assertRoutes(FastCommand.Torch(true), "torch on Caro", "flashlight on kro")
    }

    @Test
    fun englishCommandsWrittenInDevanagari() {
        assertRoutes(FastCommand.Torch(true), "टर्न ऑन द फ्लैशलाइट", "टर्न ऑन टॉर्च", "फ्लैशलाइट ऑन", "टॉर्च चालू करो")
        assertRoutes(FastCommand.Torch(false), "टर्न ऑफ द टॉर्च", "फ्लैशलाइट ऑफ", "फ़्लैशलाइट ऑफ़ करो", "टॉर्च बंद")
        assertRoutes(FastCommand.OpenApp("कैलकुलेटर"), "ओपन कैलकुलेटर", "कैलकुलेटर ओपन करो")
        assertRoutes(FastCommand.OpenApp("इंस्टाग्राम"), "ओपन इंस्टाग्राम", "इंस्टाग्राम खोलो प्लीज़")
        assertRoutes(FastCommand.OpenApp("व्हाट्सएप"), "व्हाट्सएप ओपन करो")
        assertRoutes(FastCommand.OpenApp("क्रोम"), "क्रोम खोलो")
        assertRoutes(FastCommand.OpenApp("सेटिंग्स"), "सेटिंग्स ओपन कर दो")
        assertRoutes(FastCommand.OpenApp("कैमरा"), "कैमरा खोलो ना")
        assertRoutes(FastCommand.GoHome, "होम स्क्रीन")
    }

    @Test
    fun trailingPolitenessIsIgnored() {
        assertRoutes(FastCommand.Torch(true), "torch jalao yaar", "torch jalao na", "turn on the flashlight please", "torch on karo ji")
        assertRoutes(FastCommand.Torch(false), "flashlight band karo na yaar", "टॉर्च बंद करो यार")
        assertRoutes(FastCommand.OpenApp("YouTube"), "YouTube kholo please", "open YouTube thank you", "bhai YouTube khol do")
    }

    @Test
    fun multiStepRequestsFromFluxAreNotFast() = assertRoutes(
        null,
        "Hey, Wakey. Open Instagram and search for cats.", "Instagram, खोलो और cats search करो.",
        "Instagram, खोलो और cats. Search करो.", "Instagram Holo or cat search Carol", "Instagram Hole or cat search Carol",
        "Instagram kholo aur cats search karo", "Instagram kholo or cats search karo", "ओपन इंस्टाग्राम एंड सर्च कैट्स",
        "ओपन यूट्यूब देन प्ले सॉन्ग्स", "क्या linala or colon?", "YouTube kholo, cats search karo",
    )

    @Test
    fun otherRequestsAreNotFast() = assertRoutes(
        null,
        "", "   ", "please", "open", "open the app", "search for cats", "call mom", "send a message to Priya",
        "turn on bluetooth", "what's the weather", "torch", "play despacito on youtube", "torch jalao aur camera kholo",
        "open this very long made up app name here",
    )
}
