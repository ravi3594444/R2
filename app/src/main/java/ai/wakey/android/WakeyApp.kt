package ai.wakey.android

import ai.wakey.android.agent.AgentLoop
import ai.wakey.android.agent.DeviceActions
import ai.wakey.android.audio.AudioEngine
import ai.wakey.android.config.SecretKind
import ai.wakey.android.config.SecretStore
import ai.wakey.android.config.SettingsRepository
import ai.wakey.android.core.AssistantController
import ai.wakey.android.llm.OpenAiCompatibleChatModel
import ai.wakey.android.service.Notifications
import ai.wakey.android.stt.DeepgramFluxStt
import ai.wakey.android.tts.AndroidSpeaker
import ai.wakey.android.tts.DeepgramSpeaker
import ai.wakey.android.tts.RoutingSpeaker
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WakeyApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: WakeyApp
            private set
        val graph: AppGraph get() = instance.graph

        @Volatile private var startedActivities = 0

        /** True while a Wakey screen is visible; Android only lets visible apps start activities freely. */
        val isVisible: Boolean get() = startedActivities > 0
    }
}

/**
 * Manual dependency container. Providers are created against interfaces and read credentials
 * through lambdas at request time, so a backend token service can replace them later.
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsRepository(appContext)
    val secrets = SecretStore(appContext).apply {
        // Debug builds only: keys from the git-ignored secrets.properties, copied once into
        // Keystore-encrypted storage. Release builds compile these as empty strings.
        seedIfEmpty(SecretKind.LlmApiKey, BuildConfig.SEED_FIREWORKS_API_KEY)
        seedIfEmpty(SecretKind.DeepgramApiKey, BuildConfig.SEED_DEEPGRAM_API_KEY)
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val notifications = Notifications(appContext).apply { ensureChannels() }
    val audio = AudioEngine(appContext)
    val stt = DeepgramFluxStt(http) { secrets.get(SecretKind.DeepgramApiKey) }
    val androidSpeaker = AndroidSpeaker(appContext) { settings.current }
    val deepgramSpeaker = DeepgramSpeaker(http, { secrets.get(SecretKind.DeepgramApiKey) }) { settings.current }
    val speaker = RoutingSpeaker(androidSpeaker, deepgramSpeaker) { settings.current }
    val chatModel = OpenAiCompatibleChatModel(
        http,
        baseUrl = { settings.current.llmBaseUrl },
        model = { settings.current.llmModel },
        apiKey = { secrets.get(SecretKind.LlmApiKey) },
    )
    val device = DeviceActions(appContext, screen = { WakeyAccessibilityService.controller })
    val agent = AgentLoop(chatModel, device, { WakeyAccessibilityService.controller }) { settings.current }

    val controller = AssistantController(
        appContext = appContext,
        settingsRepo = settings,
        secrets = secrets,
        audio = audio,
        stt = stt,
        speaker = speaker,
        chatModel = chatModel,
        device = device,
        agent = agent,
        notifications = notifications,
    )
}
