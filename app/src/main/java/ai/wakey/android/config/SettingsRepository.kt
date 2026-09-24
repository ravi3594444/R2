package ai.wakey.android.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Persists [WakeySettings] in private SharedPreferences and exposes them as a flow. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wakey_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<WakeySettings> = _settings.asStateFlow()

    val current: WakeySettings get() = _settings.value

    fun update(transform: (WakeySettings) -> WakeySettings) {
        _settings.update { old ->
            val new = sanitize(transform(old))
            if (new != old) save(new)
            new
        }
    }

    private fun sanitize(s: WakeySettings) = s.copy(
        wakeSensitivity = s.wakeSensitivity.coerceIn(0f, 1f),
        speechRate = s.speechRate.coerceIn(0.5f, 2.0f),
        maxAgentSteps = s.maxAgentSteps.coerceIn(1, WakeySettings.MAX_AGENT_STEPS_LIMIT),
        llmBaseUrl = s.llmBaseUrl.trim().trimEnd('/'),
        llmModel = s.llmModel.trim(),
        deepgramVoice = s.deepgramVoice.trim(),
        sttModel = s.sttModel.trim(),
        wakePhrase = WakeySettings.normalizeWakePhrase(s.wakePhrase),
    )

    private fun load(): WakeySettings {
        val d = WakeySettings()
        return WakeySettings(
            wakePhrase = prefs.getString("wake_phrase", d.wakePhrase) ?: d.wakePhrase,
            wakeSensitivity = prefs.getFloat("wake_sensitivity", d.wakeSensitivity),
            ttsEngine = enumOr(prefs.getString("tts_engine", null), d.ttsEngine),
            androidVoiceName = prefs.getString("android_voice", null),
            deepgramVoice = prefs.getString("deepgram_voice", d.deepgramVoice) ?: d.deepgramVoice,
            speechRate = prefs.getFloat("speech_rate", d.speechRate),
            speakReplies = prefs.getBoolean("speak_replies", d.speakReplies),
            languageMode = enumOr(prefs.getString("language_mode", null), d.languageMode),
            sttModel = prefs.getString("stt_model", d.sttModel) ?: d.sttModel,
            llmBaseUrl = prefs.getString("llm_base_url", d.llmBaseUrl) ?: d.llmBaseUrl,
            llmModel = prefs.getString("llm_model", d.llmModel) ?: d.llmModel,
            maxAgentSteps = prefs.getInt("max_agent_steps", d.maxAgentSteps),
            onboardingDone = prefs.getBoolean("onboarding_done", d.onboardingDone),
            floatingButton = prefs.getBoolean("floating_button", d.floatingButton),
        ).let(::sanitize)
    }

    private fun save(s: WakeySettings) {
        prefs.edit()
            .putString("wake_phrase", s.wakePhrase)
            .putFloat("wake_sensitivity", s.wakeSensitivity)
            .putString("tts_engine", s.ttsEngine.name)
            .putString("android_voice", s.androidVoiceName)
            .putString("deepgram_voice", s.deepgramVoice)
            .putFloat("speech_rate", s.speechRate)
            .putBoolean("speak_replies", s.speakReplies)
            .putString("language_mode", s.languageMode.name)
            .putString("stt_model", s.sttModel)
            .putString("llm_base_url", s.llmBaseUrl)
            .putString("llm_model", s.llmModel)
            .putInt("max_agent_steps", s.maxAgentSteps)
            .putBoolean("onboarding_done", s.onboardingDone)
            .putBoolean("floating_button", s.floatingButton)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
