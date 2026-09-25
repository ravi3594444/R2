package ai.wakey.android.config

/** Which engine speaks Wakey's replies. Kept as a setting so engines can be swapped at runtime. */
enum class TtsEngine(val label: String) {
    Android("Android voice"),
    Deepgram("Deepgram voice"),
}

/** How Wakey is woken hands-free. */
enum class WakeMode(val label: String) {
    /** Say "Hey" and the request in one go ("Hey, Instagram kholo"); Wakey acts only if a command follows. */
    HeyCommand("“Hey” + command"),

    /** Say the wake phrase ([WakeySettings.wakePhrase]), then the request. */
    Phrase("Wake phrase"),
}

/** Speech-to-text language behaviour. [hints] are Deepgram Flux `language_hint` values. */
enum class LanguageMode(val label: String, val hints: List<String>) {
    EnglishHindi("English + Hindi", listOf("en", "hi")),
    English("English", listOf("en")),
    Hindi("Hindi", listOf("hi")),
    Auto("Auto-detect", emptyList()),
}

/** Non-secret user settings. API keys live in [SecretStore], never here. */
data class WakeySettings(
    val wakeMode: WakeMode = WakeMode.Phrase,
    val wakePhrase: String = DEFAULT_WAKE_PHRASE,
    /** 0 = fewest false wakes, 1 = most eager. Mapped to sherpa-onnx boost score / threshold. */
    val wakeSensitivity: Float = 0.5f,
    val ttsEngine: TtsEngine = TtsEngine.Android,
    /** Android `Voice.name`, or null to pick the best offline voice for the reply language. */
    val androidVoiceName: String? = null,
    /** Deepgram Flux TTS model string, e.g. `flux-meena-en`. */
    val deepgramVoice: String = DEFAULT_DEEPGRAM_VOICE,
    val speechRate: Float = 1.0f,
    val speakReplies: Boolean = true,
    val languageMode: LanguageMode = LanguageMode.EnglishHindi,
    val sttModel: String = DEFAULT_STT_MODEL,
    val llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmModel: String = DEFAULT_LLM_MODEL,
    val maxAgentSteps: Int = DEFAULT_MAX_STEPS,
    /** Let the fast decision model (Jev) pick routine steps before asking the LLM. */
    val useFastDecisions: Boolean = true,
    val decisionBaseUrl: String = DEFAULT_DECISION_BASE_URL,
    val decisionModel: String = DEFAULT_DECISION_MODEL,
    val onboardingDone: Boolean = false,
    /** The user wants "Hey Wakey" on; restored after the app or phone restarts. */
    val wakeListeningWanted: Boolean = false,
    /** A short chime when the wake phrase is heard. */
    val wakeSound: Boolean = true,
    /** Raise quiet speech before the wake word detector and speech recognition hear it. */
    val micBoost: Boolean = true,
    /** Show the floating Wakey button over other apps (needs Wakey screen control). */
    val floatingButton: Boolean = false,
) {
    /** What the user says to wake Wakey, for captions and notifications: "Hey" or the wake phrase. */
    val spokenWake: String get() = if (wakeMode == WakeMode.HeyCommand) HEY else wakePhrase

    companion object {
        const val DEFAULT_WAKE_PHRASE = "Hey Wakey"
        const val HEY = "Hey"
        const val DEFAULT_DEEPGRAM_VOICE = "flux-meena-en"
        const val DEFAULT_STT_MODEL = "flux-general-multi"
        const val DEFAULT_LLM_BASE_URL = "https://api.fireworks.ai/inference/v1"
        const val DEFAULT_LLM_MODEL = "accounts/fireworks/models/deepseek-v4p1-flash"
        const val DEFAULT_MAX_STEPS = 12
        const val DEFAULT_DECISION_BASE_URL = "https://api.aimlapi.com/v1"
        const val DEFAULT_DECISION_MODEL = "typesafe/jev"
        const val MAX_AGENT_STEPS_LIMIT = 25

        /** "Hey, Wakey!" → "Hey Wakey": the keyword encoder accepts letters, apostrophes and spaces only. */
        fun normalizeWakePhrase(phrase: String): String =
            phrase.replace(Regex("[,.!?;:\"]"), " ").trim().replace(Regex("\\s+"), " ")
    }
}

/** Credentials the user can enter. Stored encrypted by [SecretStore]. */
enum class SecretKind(val label: String, val prefKey: String) {
    LlmApiKey("Fireworks / LLM API key", "llm_api_key"),
    DeepgramApiKey("Deepgram API key", "deepgram_api_key"),
    DecisionApiKey("AI/ML API key (Jev)", "decision_api_key"),
}
