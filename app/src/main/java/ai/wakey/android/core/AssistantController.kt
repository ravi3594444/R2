package ai.wakey.android.core

import ai.wakey.android.WakeyApp
import ai.wakey.android.accessibility.AccessibilityStatus
import ai.wakey.android.agent.AgentListener
import ai.wakey.android.agent.AgentLoop
import ai.wakey.android.agent.AgentPrompt
import ai.wakey.android.agent.AgentResult
import ai.wakey.android.agent.AgentStatus
import ai.wakey.android.agent.ConfirmationRequest
import ai.wakey.android.agent.DeviceActions
import ai.wakey.android.agent.FastCommand
import ai.wakey.android.agent.FastCommandRouter
import ai.wakey.android.audio.AudioEngine
import ai.wakey.android.audio.WakeChime
import ai.wakey.android.audio.WakeEvent
import ai.wakey.android.config.SecretKind
import ai.wakey.android.config.SecretStore
import ai.wakey.android.config.SettingsRepository
import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeMode
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.llm.DecisionModel
import ai.wakey.android.service.Notifications
import ai.wakey.android.service.TaskRunService
import ai.wakey.android.service.WakeService
import ai.wakey.android.stt.SpeechToText
import ai.wakey.android.stt.SttConfig
import ai.wakey.android.stt.SttError
import ai.wakey.android.stt.SttListener
import ai.wakey.android.stt.SttSession
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.tasks.TaskHarness
import ai.wakey.android.tasks.TaskKind
import ai.wakey.android.tasks.TaskParser
import ai.wakey.android.tasks.TaskReplies
import ai.wakey.android.tasks.TaskRequest
import ai.wakey.android.tasks.TaskStatus
import ai.wakey.android.tasks.TaskTime
import ai.wakey.android.tasks.WakeyTask
import ai.wakey.android.tts.RoutingSpeaker
import ai.wakey.android.tts.VoiceOption
import ai.wakey.android.ui.MainActivity
import ai.wakey.android.wake.EncodedKeyword
import ai.wakey.android.wake.KeywordEncoder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * The single pipeline every request goes through, whether it starts from the wake word, the mic
 * button, the floating button, push-to-talk, the text box or a schedule:
 *
 *   wake/mic → Deepgram stream → transcript → task harness → fast Android command | agent loop → reply → TTS
 *
 * The task harness decides when a request runs: now, after the running task ("… after this"), or at
 * a time ("call mum at 4", "ring me at 5"). A new request while a task runs replaces it; the wake
 * word only silences speech until the request is heard, so "… after this" can queue behind it.
 *
 * All state changes happen on the main thread. [stop] cancels recording, model requests, actions,
 * speech and queued tasks at once.
 */
class AssistantController(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val audio: AudioEngine,
    private val stt: SpeechToText,
    private val speaker: RoutingSpeaker,
    private val chatModel: ChatModel,
    private val decisionModel: DecisionModel,
    private val device: DeviceActions,
    private val agent: AgentLoop,
    private val notifications: Notifications,
    private val harness: TaskHarness,
    private val chime: WakeChime = WakeChime(),
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    val settings: StateFlow<WakeySettings> get() = settingsRepo.settings

    /** What is running, queued, scheduled and recently finished. */
    val tasks: StateFlow<TaskBoard> get() = harness.board

    private val ids = AtomicLong(1)
    private var session: SttSession? = null
    private var sessionToken = 0L
    private var listenWatchdog: Job? = null
    private var taskJob: Job? = null
    private var speechJob: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    /** The STT session opened to hear a spoken yes/no, closed when the confirmation resolves. */
    private var confirmSession: SttSession? = null
    private var turn: TurnClock? = null
    private var headStart: HeadStart? = null

    /** [sessionToken] of the session confirming an unsure wake detection, until it is confirmed. */
    private var checkToken = NO_CHECK
    private val _wakeStats = MutableStateFlow(WakeStats())
    val wakeStats: StateFlow<WakeStats> = _wakeStats.asStateFlow()

    /** See [AudioEngine.micMuted]. */
    val micMuted: StateFlow<Boolean> get() = audio.micMuted

    /** See [AudioEngine.boostDb]. */
    val micBoostDb: StateFlow<Int> get() = audio.boostDb
    @Volatile private var keywordEncoder: KeywordEncoder? = null

    /** Set while the floating button waits for the voice service to start; times out into opening Wakey. */
    private var listenWhenReady: Job? = null

    /** Alarm notifications that may still ring, with when they started (elapsed realtime). */
    private val ringing = mutableMapOf<Long, Long>()

    /**
     * The task whose work is on screen. A replaced task finishes cancelling after its successor has
     * started, so its clean-up must not clear the successor's state.
     */
    private var activeTaskId: Long? = null

    init {
        scope.launch { audio.level.collect { level -> _state.update { it.copy(micLevel = level) } } }
        // Wakey's own voice in the microphone must not teach the mic boost that the room is loud.
        scope.launch {
            _state.map { it.phase == AssistantPhase.Speaking }.distinctUntilChanged().collect { audio.holdBoost = it }
        }
        scope.launch {
            settingsRepo.settings.map { Triple(it.wakeMode, it.wakePhrase, it.wakeSensitivity) }.distinctUntilChanged().drop(1)
                .collect { (mode, phrase, sensitivity) ->
                    if (_state.value.wakeWordEnabled) {
                        runCatching { audio.updateWakePhrase(phrase, sensitivity, mode) }
                            .onSuccess { setStatus("Now listening for “${settingsRepo.current.spokenWake}”.") }
                            .onFailure { setStatus("Wake phrase not applied: ${it.message}", error = true) }
                    }
                }
        }
        appContext.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    scope.launch {
                        if (session != null && !isOnline()) {
                            cancelListening()
                            reportProblem("Lost the internet connection.")
                        }
                    }
                }
            },
        )
        // Bind Android TTS early so Settings can list installed voices on first open.
        speaker.android.availableVoices()
        // The mic reopens itself after e.g. a phone call; only a lasting failure is shown.
        scope.launch { audio.micProblem.filterNotNull().collect { setStatus(it, error = true) } }
        scope.launch {
            audio.micMuted.drop(1).collect { muted ->
                if (muted) {
                    _wakeStats.update { it.copy(mutedEvents = it.mutedEvents + 1) }
                    setStatus(MIC_MUTED_MESSAGE, error = true)
                } else if (_state.value.statusMessage == MIC_MUTED_MESSAGE) {
                    setStatus(null)
                }
            }
        }
        // WakeService itself mirrors state into its notification; it reports start failures here.
        scope.launch {
            WakeService.startProblem.filterNotNull().collect { problem ->
                if (listenWhenReady != null) openAppToListen() else setStatus(problem, error = true)
            }
        }
        // Scheduled tasks that came due while the phone was locked run once it is unlocked.
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onUnlocked()
            },
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Anything that came due while Wakey wasn't running. Posted: the app graph is still being built.
        scope.launch(Dispatchers.Main) { onTaskAlarm() }
    }

    // ------------------------------------------------------------------ voice service lifecycle

    /** The wake word switch, from the visible UI. Starting the foreground service from there satisfies Android 14+. */
    fun setWakeListening(context: Context, enabled: Boolean) {
        settingsRepo.update { it.copy(wakeListeningWanted = enabled) }
        when {
            enabled && _state.value.wakeServiceRunning -> {
                if (!_state.value.wakeWordEnabled) startWakeWord()
                // Started again while Wakey is visible: renews its microphone access for the background.
                WakeService.start(context)
            }
            enabled -> WakeService.start(context)
            else -> {
                stopWakeWord()
                if (!buttonWantsService()) WakeService.stop(context)
            }
        }
    }

    /**
     * The floating button switch, from the visible UI. The voice service keeps running for it (with
     * or without the wake word), because only a running microphone service may record from the
     * background, which is where the button is tapped.
     */
    fun setFloatingButton(context: Context, enabled: Boolean) {
        settingsRepo.update { it.copy(floatingButton = enabled) }
        // It never shows over Wakey's own screens, where the switch is, so say where to find it.
        if (enabled && AccessibilityStatus.isEnabled(context)) setStatus(FLOATING_BUTTON_ON)
        else if (_state.value.statusMessage == FLOATING_BUTTON_ON) setStatus(null)
        when {
            // The button is drawn by screen control; until that's on, [restoreWakeListening] starts the service later.
            enabled && !_state.value.wakeServiceRunning && AccessibilityStatus.isEnabled(context) -> WakeService.start(context)
            !enabled && !settingsRepo.current.wakeListeningWanted -> WakeService.stop(context)
        }
    }

    /**
     * Turns the voice service back on if the wake word or the floating button needs it: when Wakey
     * is opened, when its assistant panel shows, and when Android rebinds Wakey as the default
     * assistant after a reboot or after its process was killed. A service that is already running is
     * started again: done while Wakey is visible, that renews its right to use the microphone in the
     * background, which Android may have withheld when the service last started (the microphone then
     * records only silence).
     */
    fun restoreWakeListening(context: Context) {
        val wakeWanted = settingsRepo.current.wakeListeningWanted
        if (!wakeWanted && !buttonWantsService()) return
        // Kept running by the floating button while the wake word failed to start: try it again.
        if (wakeWanted && _state.value.wakeServiceRunning && !_state.value.wakeWordEnabled) startWakeWord()
        WakeService.start(context)
    }

    /** The floating button is on and can show: it is drawn by Wakey's screen control. */
    private fun buttonWantsService(): Boolean =
        settingsRepo.current.floatingButton && AccessibilityStatus.isEnabled(appContext)

    /** Called by [WakeService] once it is in the foreground. Returns false if it has nothing to do. */
    fun onWakeServiceStarted(): Boolean {
        val buttonWaiting = listenWhenReady != null
        val wakeWord = settingsRepo.current.wakeListeningWanted && startWakeWord()
        // Switched off while starting, or the wake word failed with nothing else needing the service.
        if (!wakeWord && !buttonWantsService() && !buttonWaiting) return false
        _state.update { it.copy(wakeServiceRunning = true) }
        if (buttonWaiting) {
            listenWhenReady?.cancel()
            listenWhenReady = null
            listen(InputSource.Button)
        }
        return true
    }

    /** Called by [WakeService] when it stops for any reason. The switches keep what the user chose. */
    fun onWakeServiceStopped() {
        stopWakeWord()
        _state.update { it.copy(wakeServiceRunning = false) }
    }

    /** Notification "Turn off": stop everything, hide the floating button and end the voice service. */
    fun turnOff() {
        stop(silent = true)
        settingsRepo.update { it.copy(wakeListeningWanted = false, floatingButton = false) }
        WakeService.stop(appContext)
    }

    private fun startWakeWord(): Boolean {
        val s = settingsRepo.current
        return try {
            audio.startWakeListening(s.wakePhrase, s.wakeSensitivity, s.wakeMode, onWake = ::onWakeDetected)
            _state.update {
                it.copy(wakeWordEnabled = true, phase = if (it.phase == AssistantPhase.Idle) AssistantPhase.WakeListening else it.phase)
            }
            setStatus(
                if (s.wakeMode == WakeMode.HeyCommand) "Say “Hey” and your request, like “Hey, open YouTube”."
                else "Say “${s.spokenWake}” followed by your request.",
            )
            true
        } catch (e: Exception) {
            setStatus("Could not start wake listening: ${e.message}", error = true)
            false
        }
    }

    private fun stopWakeWord() {
        audio.stopWakeListening()
        _state.update {
            it.copy(wakeWordEnabled = false, phase = if (it.phase == AssistantPhase.WakeListening) AssistantPhase.Idle else it.phase)
        }
    }

    // ------------------------------------------------------------------ voice input

    private fun onWakeDetected(event: WakeEvent) {
        scope.launch {
            if (session != null) return@launch
            if (event.needsCheck) {
                // Unsure: listen silently until the transcript shows whether the wake phrase was said.
                // Never interrupts anything, since it may well be other speech.
                if (pendingConfirm != null || taskJob?.isActive == true || speechJob?.isActive == true) return@launch
                startListening(InputSource.WakeWord, event, check = true)
                return@launch
            }
            _wakeStats.update { it.copy(wakes = it.wakes + 1) }
            val confirmation = pendingConfirm
            if (confirmation != null) {
                // "Hey Wakey, yes" answers the pending confirmation instead of starting a new task.
                speaker.stop()
                playWakeSound()
                confirmSession = startListening(InputSource.WakeWord, event, confirmation)
                return@launch
            }
            // Barge-in silences Wakey, but a running task keeps going until the request is heard:
            // "… after this" queues behind it, anything else replaces it.
            interruptSpeech()
            playWakeSound()
            startListening(InputSource.WakeWord, event)
        }
    }

    /** A command after "hey" can take a while to reach its verb ("hey Instagram pe cats search karo"). */
    private fun checkTimeoutMs() = if (settingsRepo.current.wakeMode == WakeMode.HeyCommand) HEY_CHECK_TIMEOUT_MS else CHECK_TIMEOUT_MS

    private fun playWakeSound() {
        if (!settingsRepo.current.wakeSound) return
        audio.holdBoostFor(CHIME_HOLD_MS)
        chime.play()
    }

    /** Drops an unconfirmed wake check so a tap or the assistant gesture can use the microphone. */
    private fun cancelCheck() {
        if (session != null && checkToken == sessionToken) cancelListening()
    }

    /** The transcript opened with the wake phrase: respond as to a sure detection. */
    private fun confirmCheck() {
        checkToken = NO_CHECK
        _wakeStats.update { it.copy(checksConfirmed = it.checksConfirmed + 1) }
        playWakeSound()
        warmUp()
        _state.update { it.copy(phase = AssistantPhase.Hearing, liveTranscript = "", statusMessage = null, statusIsError = false) }
    }

    /** While the user talks, opens connections to what the request will need: the reply voice and the models. */
    private fun warmUp() {
        val s = settingsRepo.current
        if (s.speakReplies && s.ttsEngine == TtsEngine.Deepgram) speaker.deepgram.prewarmConnection()
        if (secrets.has(SecretKind.LlmApiKey)) chatModel.warmUp()
        if (s.useFastDecisions && secrets.has(SecretKind.DecisionApiKey)) decisionModel.warmUp()
    }

    /** Not the wake phrase (or nothing heard in time): close the stream without a word. */
    private fun rejectCheck(heard: String) {
        checkToken = NO_CHECK
        _wakeStats.update {
            it.copy(checksRejected = it.checksRejected + 1, lastRejectedHeard = heard.take(MAX_HEARD_CHARS).ifBlank { it.lastRejectedHeard })
        }
        cancelListening()
    }

    /** Mic button: tap to talk, tap again to finish early. */
    fun onMicTap() = listen(InputSource.Mic)

    /**
     * The floating button: talk without the wake word; tap again to finish early. Recording from the
     * background needs the voice service, so it is started first if needed; if Android refuses, Wakey
     * opens and listens there instead.
     */
    fun onFloatingButtonTap() {
        if (session != null || _state.value.wakeServiceRunning) {
            listen(InputSource.Button)
            return
        }
        if (listenWhenReady != null) return
        listenWhenReady = scope.launch {
            delay(VOICE_SERVICE_START_TIMEOUT_MS)
            openAppToListen()
        }
        WakeService.start(appContext)
        // A refusal on the spot (no microphone permission, background start blocked) may already have
        // been handled by the startProblem collector, which clears listenWhenReady.
        if (listenWhenReady != null && WakeService.startProblem.value != null) openAppToListen()
    }

    /** Wakey's assistant session was opened (e.g. power button held): listen as if "Hey Wakey" was said. */
    fun onAssistInvoked() {
        cancelCheck()
        if (session != null) return
        pendingConfirm?.let { confirmation ->
            speaker.stop()
            confirmSession = startListening(InputSource.Assistant, null, confirmation)
            return
        }
        interruptSpeech()
        startListening(InputSource.Assistant, null)
    }

    fun onPushToTalkPressed() {
        cancelCheck()
        if (session != null) return
        interruptSpeech()
        _state.update { it.copy(pushToTalkActive = true) }
        startListening(InputSource.PushToTalk, null)
    }

    fun onPushToTalkReleased() {
        _state.update { it.copy(pushToTalkActive = false) }
        session?.endTurn()
    }

    private fun listen(source: InputSource) {
        cancelCheck()
        if (session != null) {
            session?.endTurn()
            return
        }
        interruptSpeech()
        startListening(source, null)
    }

    /** The floating button's fallback: Wakey's own screen may always use the microphone. */
    private fun openAppToListen() {
        listenWhenReady?.cancel()
        listenWhenReady = null
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!device.launchActivity(intent)) setStatus("Open Wakey to talk.", error = true)
    }

    /**
     * Opens a Deepgram session fed by the microphone. With [confirmation], the transcript answers that
     * pending approval instead of becoming a new request. With [check], the session first confirms an
     * unsure wake detection: nothing shows or sounds until the transcript opens with the wake phrase,
     * and it closes silently if it doesn't. Returns the session, or null if none started.
     */
    private fun startListening(
        source: InputSource,
        wake: WakeEvent?,
        confirmation: CompletableDeferred<Boolean>? = null,
        reopened: Boolean = false,
        check: Boolean = false,
    ): SttSession? {
        // Never leave an earlier session streaming behind the new one.
        if (session != null) cancelListening()
        val s = settingsRepo.current
        if (!secrets.has(SecretKind.DeepgramApiKey)) {
            audio.stopCommandStream()
            if (!check) reportProblem("Add your Deepgram API key in Settings to use voice.")
            return null
        }
        if (!isOnline()) {
            audio.stopCommandStream()
            if (!check) reportProblem("No internet connection. Voice needs Deepgram; typed direct commands still work.")
            return null
        }
        val clock = TurnClock(source, SystemClock.elapsedRealtime(), wake?.detectionLatencyMs)
        if (confirmation == null) turn = clock
        val stripPhrase = if (s.wakeMode == WakeMode.HeyCommand) HEY_STRIP else s.spokenWake
        // What Flux took for the whole request when it said the turn was probably over.
        var likelyRequest: String? = null
        val token = ++sessionToken
        checkToken = if (check) token else NO_CHECK
        if (!check) {
            _state.update {
                it.copy(phase = AssistantPhase.Hearing, liveTranscript = "", statusMessage = null, statusIsError = false)
            }
        }
        val listener = object : SttListener {
            override fun onConnected(connectMs: Long) = post(token) {
                clock.sttConnectMs = connectMs
                clock.lastEventAt = SystemClock.elapsedRealtime()
            }

            override fun onSpeechStarted() = post(token) {
                clock.speechStarted = true
                clock.lastEventAt = SystemClock.elapsedRealtime()
            }

            override fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long?) =
                post(token) {
                    clock.lastEventAt = SystemClock.elapsedRealtime()
                    if (checkToken == token) {
                        val verdict = if (s.wakeMode == WakeMode.HeyCommand) WakeTranscript.checkHeyCommand(text, isFinal)
                        else WakeTranscript.check(text, s.spokenWake, isFinal)
                        when (verdict) {
                            WakeTranscript.Verdict.Undecided -> return@post
                            WakeTranscript.Verdict.NotHeard -> {
                                rejectCheck(text)
                                return@post
                            }
                            WakeTranscript.Verdict.Heard -> confirmCheck()
                        }
                    }
                    val cleaned = stripWakePhrase(text, stripPhrase)
                    if (!isFinal) {
                        if (cleaned.isNotBlank()) clock.speechStarted = true
                        // More words after a likely end: the user is still talking.
                        val resumed = likelyRequest?.let { !sameWords(it, cleaned) } == true
                        if (resumed) {
                            likelyRequest = null
                            dropHeadStart()
                        }
                        _state.update {
                            it.copy(liveTranscript = cleaned, phase = if (resumed && it.phase == AssistantPhase.Thinking) AssistantPhase.Hearing else it.phase)
                        }
                        return@post
                    }
                    clock.transcriptionMs = transcriptionMs
                    clock.speechSessionMs = SystemClock.elapsedRealtime() - clock.startedAt
                    finishListening(keepHeadStart = true)
                    when {
                        confirmation != null -> answerByVoice(cleaned, confirmation)
                        // Only "Hey Wakey" was heard (the user paused): keep listening for the request once.
                        cleaned.isBlank() && text.isNotBlank() && source == InputSource.WakeWord && !reopened ->
                            startListening(InputSource.WakeWord, wake, reopened = true)
                        cleaned.isBlank() -> reportProblem("I didn't catch that.", speak = source == InputSource.WakeWord)
                        else -> handleUtterance(cleaned, source, languages)
                    }
                    // The request has taken over a head start for its words, if it could use one.
                    dropHeadStart()
                }

            // Flux thinks the request is complete: show that Wakey is on it and let the agent start.
            // Only the final transcript ends the turn, so going on talking just returns to listening.
            override fun onEndLikely(transcript: String) = post(token) {
                if (checkToken == token || confirmation != null) return@post
                val cleaned = stripWakePhrase(transcript, stripPhrase)
                if (cleaned.isBlank()) return@post
                likelyRequest = cleaned
                _state.update { if (it.phase == AssistantPhase.Hearing) it.copy(phase = AssistantPhase.Thinking, liveTranscript = cleaned) else it }
                startHeadStart(cleaned, clock)
            }

            override fun onTurnResumed() = post(token) {
                if (likelyRequest == null) return@post
                likelyRequest = null
                dropHeadStart()
                _state.update { if (it.phase == AssistantPhase.Thinking) it.copy(phase = AssistantPhase.Hearing) else it }
            }

            override fun onError(error: SttError) = post(token) {
                if (checkToken == token) {
                    rejectCheck("")
                    return@post
                }
                finishListening()
                val message = when (error.kind) {
                    SttError.Kind.MissingKey -> "Add your Deepgram API key in Settings."
                    SttError.Kind.Auth -> "Deepgram rejected the API key."
                    SttError.Kind.Network -> "Lost the connection to Deepgram."
                    else -> "Speech recognition failed: ${error.message}"
                }
                if (confirmation == null) reportProblem(message)
            }
        }
        val newSession = try {
            val config = SttConfig(
                model = s.sttModel,
                languageHints = s.languageMode.hints,
                keyterms = keyterms(s),
                hasRequest = { partial -> stripWakePhrase(partial, stripPhrase).isNotBlank() },
            )
            stt.open(config, listener)
        } catch (e: Exception) {
            checkToken = NO_CHECK
            audio.stopCommandStream()
            if (!check) reportProblem("Could not start speech recognition: ${e.message}")
            return null
        }
        session = newSession
        try {
            audio.startCommandStream { buffer, length -> newSession.sendPcm(buffer, length) }
        } catch (e: Exception) {
            checkToken = NO_CHECK
            cancelListening()
            if (!check) reportProblem("Could not use the microphone: ${e.message}")
            return null
        }
        if (!check) warmUp()
        listenWatchdog?.cancel()
        listenWatchdog = scope.launch { watchSession(newSession, clock, confirmation != null) }
        return newSession
    }

    /** Ends a session that hears nothing, stalls (e.g. the network died mid-stream) or runs too long. */
    private suspend fun watchSession(watched: SttSession, clock: TurnClock, forConfirmation: Boolean) {
        val pushToTalk = clock.source == InputSource.PushToTalk
        val maxMs = if (pushToTalk) MAX_PUSH_TO_TALK_MS else MAX_UTTERANCE_MS
        var endRequested = false
        while (session === watched) {
            delay(WATCHDOG_TICK_MS)
            if (session !== watched) return
            val now = SystemClock.elapsedRealtime()
            when {
                checkToken == sessionToken -> if (now - clock.startedAt > checkTimeoutMs()) {
                    rejectCheck("")
                    return
                }
                !clock.speechStarted && !pushToTalk && now - clock.startedAt > NO_SPEECH_TIMEOUT_MS -> {
                    cancelListening()
                    if (forConfirmation) return
                    if (clock.sttConnectMs == null) reportProblem("Couldn't reach Deepgram. Check your internet connection.")
                    else reportProblem("I didn't hear anything.", speak = false)
                    return
                }
                clock.speechStarted && now - clock.lastEventAt > STALL_TIMEOUT_MS -> {
                    cancelListening()
                    if (!forConfirmation) reportProblem("Lost the connection to Deepgram.")
                    return
                }
                !endRequested && now - clock.startedAt > maxMs -> {
                    endRequested = true
                    watched.endTurn()
                }
            }
        }
    }

    private fun post(token: Long, block: () -> Unit) {
        scope.launch { if (token == sessionToken) block() }
    }

    /**
     * Stop streaming audio; the STT session closes itself after a final transcript. A head start is
     * dropped unless [keepHeadStart]: the final transcript hands it to the request it heard.
     */
    private fun finishListening(keepHeadStart: Boolean = false) {
        if (!keepHeadStart) dropHeadStart()
        checkToken = NO_CHECK
        listenWatchdog?.cancel()
        listenWatchdog = null
        session = null
        sessionToken++
        audio.stopCommandStream()
        _state.update {
            it.copy(liveTranscript = "", pushToTalkActive = false, phase = if (it.phase == AssistantPhase.Hearing) busyPhase(it) else it.phase)
        }
        // Queued tasks wait while the user talks. Posted, so a request heard just now starts first.
        scope.launch(Dispatchers.Main) { runNextIfIdle() }
    }

    private fun cancelListening() {
        session?.cancel()
        finishListening()
    }

    // ------------------------------------------------------------------ text input

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        cancelListening()
        interruptSpeech()
        turn = TurnClock(InputSource.Text, SystemClock.elapsedRealtime(), null)
        handleUtterance(trimmed, InputSource.Text, emptyList())
    }

    // ------------------------------------------------------------------ requests and tasks

    private fun handleUtterance(text: String, source: InputSource, languages: List<String>) {
        val clock = turn ?: TurnClock(source, SystemClock.elapsedRealtime(), null)
        clock.requestAt = SystemClock.elapsedRealtime()
        if (isStopPhrase(text)) {
            addEntry(Speaker.User, text, source = source)
            stop()
            return
        }
        val busy = taskJob?.isActive == true
        val time = now()
        val request = TaskParser.parse(text, time)
        // Another app's ringing alarm (e.g. the Clock app's): the agent can stop it on screen.
        val stopsOtherAlarm = request is TaskRequest.CancelScheduled && request.ringingOnly && !isRinging()
        // A request that replaces the running task stops it first, so the conversation shows the
        // old request as stopped before the new one, and the model never resumes it.
        if (busy && (request is TaskRequest.Now || stopsOtherAlarm)) cancelWork()
        addEntry(Speaker.User, text, source = source)
        when (request) {
            is TaskRequest.Now -> runTask(harness.start(request.text), languages, clock)
            is TaskRequest.AfterCurrent ->
                if (busy) {
                    harness.enqueue(request.text)
                    acknowledge(TaskReplies.queued(request.text), languages, clock)
                } else {
                    runTask(harness.start(request.text), languages, clock)
                }
            is TaskRequest.Scheduled -> {
                schedule(request)
                acknowledge(TaskReplies.scheduled(request, time, text), languages, clock)
            }
            TaskRequest.ListTasks -> acknowledge(TaskReplies.list(harness.board.value, time), languages, clock)
            is TaskRequest.CancelScheduled ->
                if (stopsOtherAlarm) runTask(harness.start(text), languages, clock)
                else acknowledge(cancelScheduled(request, time), languages, clock)
        }
    }

    /**
     * Runs [task]: a direct Android command or the agent, then the spoken reply. [source] is set for
     * tasks that were queued or scheduled earlier; a scheduled run gets no conversation history, which
     * would only show it being scheduled.
     */
    private fun runTask(task: WakeyTask, languages: List<String>, clock: TurnClock, source: InputSource? = null) {
        val deferred = source == InputSource.Queued || source == InputSource.Scheduled
        val requestEntry = _state.value.entries.lastOrNull { it.speaker == Speaker.User }?.id
        // Started on Flux's likely end of turn for these same words: this task takes it over. Any
        // other task may change the screen the head start has read, so that drops it.
        val early = headStart?.takeIf { source == null && sameWords(it.goal, task.text) }
        if (early != null) headStart = null else dropHeadStart()
        val job = scope.launch {
            activeTaskId = task.id
            var reply: String
            var isError = false
            try {
                _state.update {
                    it.copy(
                        phase = AssistantPhase.Thinking, recentActions = emptyList(), currentAction = null,
                        taskEntryId = requestEntry, taskRunning = true,
                    )
                }
                val fast = fastCommandFor(task.text)
                if (fast != null) {
                    clock.route = "fast"
                    val info = AgentActionInfo(1, 1, describe(fast), "fast_command")
                    _state.update { it.copy(phase = AssistantPhase.Acting, currentAction = info) }
                    clock.firstActionAt = SystemClock.elapsedRealtime()
                    val outcome = device.execute(fast)
                    val done = info.copy(result = outcome.message, success = outcome.success)
                    _state.update { it.copy(currentAction = done, recentActions = listOf(done)) }
                    reply = outcome.message
                    isError = !outcome.success
                } else if (!secrets.has(SecretKind.LlmApiKey)) {
                    clock.route = "agent"
                    reply = "Add your Fireworks API key in Settings so I can handle that."
                    isError = true
                } else {
                    clock.route = "agent"
                    val result = if (early != null) {
                        clock.headStartMs = clock.requestAt - early.startedAt
                        early.goAhead.complete(Unit)
                        early.run.await()
                    } else {
                        agent.run(task.text, agentListener(clock, withHistory = source != InputSource.Scheduled), deferred)
                    }
                    clock.steps = result.steps
                    clock.llmCalls = result.llmCalls
                    clock.decisionCalls = result.decisionCalls
                    clock.timeline = result.timeline
                    clock.promptTokens = result.promptTokens
                    clock.completionTokens = result.completionTokens
                    result.firstActionAtMs?.let { clock.firstActionAt = it }
                    reply = result.reply
                    isError = result.status == AgentStatus.Failed || result.status == AgentStatus.Timeout
                    if (result.status == AgentStatus.Cancelled) {
                        harness.finish(task.id, TaskStatus.Cancelled, null)
                        endTask(task.id)
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                harness.finish(task.id, TaskStatus.Cancelled, null)
                endTask(task.id)
                throw e
            } catch (e: Exception) {
                reply = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                isError = true
            }
            harness.finish(task.id, if (isError) TaskStatus.Failed else TaskStatus.Done, reply)
            endTask(task.id)
            val entryId = addEntry(Speaker.Wakey, reply, isError = isError)
            // Nobody asked just now, so the reply may go unheard.
            if (source == InputSource.Scheduled && !WakeyApp.isVisible) notifications.showTaskResult(task, reply, isError)
            speakReply(reply, languages, clock, entryId)
        }
        taskJob = job
        job.invokeOnCompletion {
            // A direct command, or a stop, leaves the head start unused.
            early?.cancel()
            scope.launch {
                if (taskJob === job) taskJob = null
                runNextIfIdle()
            }
        }
    }

    /** The direct Android command for [text], unless the agent should handle it. */
    private suspend fun fastCommandFor(text: String): FastCommand? =
        // A garbled app name ("u two colo") is better handled by the agent than a "no such app" reply.
        FastCommandRouter.route(text)?.takeUnless {
            it is FastCommand.OpenApp && secrets.has(SecretKind.LlmApiKey) && !device.canOpen(it.appName)
        }

    /**
     * An agent run started when Flux said the request was probably complete, before the final
     * transcript: it reads the screen and asks the model meanwhile, and waits before its first
     * action. The final transcript takes it over when it has the same words; otherwise it is dropped.
     */
    private class HeadStart(val goal: String, val startedAt: Long, val goAhead: CompletableDeferred<Unit>, val run: Deferred<AgentResult>) {
        fun cancel() = run.cancel()
    }

    /** On a likely end of turn, starts the agent on [text] when that is where the request will go. */
    private fun startHeadStart(text: String, clock: TurnClock) {
        val goal = when (val request = TaskParser.parse(text, now())) {
            is TaskRequest.Now -> request.text
            is TaskRequest.AfterCurrent -> request.text
            else -> null
        }
        if (goal != null && headStart?.let { sameWords(it.goal, goal) } == true) return
        dropHeadStart()
        // Only when the request will surely run the agent at once, with nothing on screen changing.
        if (goal == null || isStopPhrase(text) || taskJob?.isActive == true || !secrets.has(SecretKind.LlmApiKey)) return
        val goAhead = CompletableDeferred<Unit>()
        val listener = agentListener(clock, withHistory = true, requestShown = false)
        headStart = HeadStart(goal, SystemClock.elapsedRealtime(), goAhead, scope.async {
            // A direct command (the flashlight, an installed app) runs without the agent.
            if (fastCommandFor(goal) != null) awaitCancellation()
            agent.run(goal, listener) { goAhead.await() }
        })
    }

    private fun dropHeadStart() {
        headStart?.cancel()
        headStart = null
    }

    /** The work of [taskId] is over (its reply may still be spoken), unless another task took over. */
    private fun endTask(taskId: Long) {
        if (activeTaskId != taskId) return
        activeTaskId = null
        _state.update { it.copy(taskRunning = false) }
    }

    /** Starts the next queued task once nothing is running, listening or waiting for an answer. */
    private fun runNextIfIdle() {
        if (taskJob?.isActive == true || session != null || pendingConfirm != null) return
        val next = harness.nextQueued() ?: return
        val task = harness.markRunning(next.id) ?: return
        val source = if (task.dueAtMs != null) InputSource.Scheduled else InputSource.Queued
        val clock = TurnClock(source, SystemClock.elapsedRealtime(), null)
        turn = clock
        addEntry(Speaker.User, task.text, source = source)
        if (source == InputSource.Scheduled && !WakeService.isRunning && !WakeyApp.isVisible) TaskRunService.start(appContext)
        runTask(task, emptyList(), clock, source)
    }

    /** A spoken reply that needs no task: a schedule confirmation, the task list, a cancellation. */
    private fun acknowledge(reply: String, languages: List<String>, clock: TurnClock) {
        clock.route = "tasks"
        // With a task running, its phase and steps stay on screen; otherwise this reply is the turn.
        val ownsPhase = taskJob?.isActive != true
        if (ownsPhase) _state.update { it.copy(recentActions = emptyList(), currentAction = null, taskEntryId = null) }
        val entryId = addEntry(Speaker.Wakey, reply)
        speechJob?.cancel()
        speechJob = scope.launch { speakReply(reply, languages, clock, entryId, ownsPhase) }
    }

    private fun schedule(request: TaskRequest.Scheduled): WakeyTask =
        harness.schedule(request.text, request.kind, request.at.toInstant().toEpochMilli())

    private fun cancelScheduled(request: TaskRequest.CancelScheduled, time: ZonedDateTime): String {
        // "Turn off the alarm" while one rings stops it rather than cancelling the next one.
        if (request.kind == TaskKind.Alarm && !request.all && request.query == null && isRinging()) {
            silenceAlarms()
            return "Alarm stopped."
        }
        val cancelled = harness.cancelScheduled(request.kind, request.all, request.query)
        cancelled.forEach { notifications.cancelTask(it.id) }
        return TaskReplies.cancelled(cancelled, request, time)
    }

    /** The task alarm fired, or Wakey (re)started: ring, remind, run or report whatever is due. */
    fun onTaskAlarm() {
        harness.batch { processDueTasks() }
        runNextIfIdle()
    }

    private fun processDueTasks() {
        harness.rearm()
        val time = now()
        val nowMs = time.toInstant().toEpochMilli()
        for (task in harness.expireWaiting()) {
            notifications.showTaskMissed(task, task.title, "The phone stayed locked, so Wakey couldn't do it.")
        }
        for (task in harness.dueNow()) {
            val dueMs = task.dueAtMs ?: nowMs
            val due = TaskTime.at(dueMs, time.zone)
            val late = nowMs - dueMs > TaskHarness.MISSED_AFTER_MS
            val dueText = TaskTime.spoken(due, time)
            when {
                task.kind == TaskKind.Reminder -> {
                    val text = TaskReplies.reminderDue(task)
                    harness.finish(task.id, if (late) TaskStatus.Missed else TaskStatus.Done, if (late) "Shown late; it was due $dueText." else null)
                    notifications.showReminder(task, if (late) "$text (It was due $dueText.)" else text)
                    if (!late) announce(text)
                }
                late -> {
                    harness.finish(task.id, TaskStatus.Missed, "It was due $dueText, when Wakey couldn't run.")
                    val reason = "It was due $dueText, but the phone was off or Wakey wasn't running."
                    if (task.kind == TaskKind.Task) notifications.showTaskMissed(task, task.title, reason)
                }
                task.kind == TaskKind.Alarm -> {
                    harness.finish(task.id, TaskStatus.Done, "Rang $dueText.")
                    ringing[task.id] = SystemClock.elapsedRealtime()
                    notifications.showAlarm(task, alarmTitle(task), TaskTime.clock(due))
                }
                needsUnlock(task) && device.isLocked -> {
                    harness.markWaiting(task.id)
                    notifications.showTaskWaiting(task, TaskReplies.sentenceCase(task.title))
                    announce(TaskReplies.unlockToRun(task))
                }
                else -> harness.queue(task.id)
            }
        }
    }

    private fun onUnlocked() {
        val released = harness.releaseWaiting()
        if (released.isEmpty()) return
        released.forEach { notifications.cancelTask(it.id) }
        runNextIfIdle()
    }

    /** "Run now" on a notification or in the task list: the task goes to the front of the queue. */
    fun runTaskNow(id: Long) {
        val task = harness.find(id) ?: return
        if (task.kind != TaskKind.Task) return
        harness.runNext(id)
        runNextIfIdle()
    }

    fun snoozeTask(id: Long) {
        silenceAlarm(id)
        harness.snooze(id, SNOOZE_MS)
    }

    /** "Stop" on a ringing alarm or "Done" on a reminder. */
    fun dismissTask(id: Long) {
        ringing.remove(id)
        notifications.cancelTask(id)
    }

    /** Cancels one task. The running one is skipped, and the queue moves on. */
    fun cancelTask(id: Long) {
        silenceAlarm(id)
        notifications.cancelTask(id)
        if (harness.board.value.running?.id == id && taskJob?.isActive == true) {
            cancelWork()
            activeTaskId = null
            _state.update { it.copy(phase = if (session != null) AssistantPhase.Hearing else restingPhase(), currentAction = null, taskRunning = false) }
        } else {
            harness.cancel(id)
        }
    }

    fun clearFinishedTasks() = harness.clearRecent()

    /** Speaks a reminder or a task that waits for an unlock, unless the phone is silenced or busy. */
    private fun announce(text: String) {
        if (!settingsRepo.current.speakReplies || session != null || taskJob?.isActive == true) return
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL || audioManager.mode != AudioManager.MODE_NORMAL) return
        speechJob?.cancel()
        speechJob = scope.launch { runCatching { speaker.speak(text, languageTagFor(text, emptyList())) } }
    }

    private fun needsUnlock(task: WakeyTask): Boolean = FastCommandRouter.route(task.text) !is FastCommand.Torch

    private fun alarmTitle(task: WakeyTask): String = when (task.text) {
        TaskReplies.WAKE_UP -> "Time to wake up"
        TaskReplies.TIMER -> "Time's up"
        "" -> "Alarm"
        else -> task.text
    }

    private fun isRinging(): Boolean {
        val cutoff = SystemClock.elapsedRealtime() - Notifications.ALARM_RING_MS
        return ringing.values.any { it > cutoff }
    }

    private fun silenceAlarm(id: Long) {
        if (ringing.remove(id) != null) notifications.cancelTask(id)
    }

    private fun silenceAlarms() {
        ringing.keys.forEach(notifications::cancelTask)
        ringing.clear()
    }

    private suspend fun speakReply(reply: String, languages: List<String>, clock: TurnClock, entryId: Long, ownsPhase: Boolean = true) {
        val s = settingsRepo.current
        if (s.speakReplies && reply.isNotBlank()) {
            // The user may already be talking again (barge-in); their turn keeps the screen.
            if (ownsPhase && session == null) _state.update { it.copy(phase = AssistantPhase.Speaking) }
            try {
                speaker.speak(reply, languageTagFor(reply, languages)) {
                    clock.replyStartAt = SystemClock.elapsedRealtime()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Could not speak the reply: ${e.message}", error = true)
            }
        }
        clock.doneAt = SystemClock.elapsedRealtime()
        val timings = clock.toTimings()
        _state.update { st ->
            val timed = st.copy(lastTimings = timings, entries = st.entries.map { if (it.id == entryId) it.copy(timings = timings) else it })
            when {
                // A queued task may have started while this was spoken; its phase and step stay.
                !ownsPhase || st.taskRunning -> timed
                st.phase == AssistantPhase.Hearing -> timed.copy(currentAction = null)
                else -> timed.copy(phase = busyPhase(timed), currentAction = null)
            }
        }
    }

    /** [requestShown]: the request is already the conversation's last entry, which the history leaves out. */
    private fun agentListener(clock: TurnClock, withHistory: Boolean, requestShown: Boolean = true) = object : AgentListener {
        override fun onAction(action: AgentActionInfo) {
            if (action.result == null && action.toolName !in PASSIVE_TOOLS && clock.firstActionAt == null) {
                clock.firstActionAt = SystemClock.elapsedRealtime()
            }
            _state.update { st ->
                val others = st.recentActions.filterNot { it.step == action.step && it.toolName == action.toolName }
                st.copy(
                    // Once acting, stay acting between steps: the step list shows the thinking and the orb doesn't flicker.
                    // While the user talks over a running task, their turn keeps the screen.
                    phase = when {
                        st.phase == AssistantPhase.Hearing -> AssistantPhase.Hearing
                        action.result == null || st.phase == AssistantPhase.Acting -> AssistantPhase.Acting
                        else -> AssistantPhase.Thinking
                    },
                    currentAction = action,
                    recentActions = (others + action).takeLast(MAX_RECENT_ACTIONS),
                )
            }
        }

        override suspend fun confirm(request: ConfirmationRequest): Boolean = askConfirmation(request)

        override fun history(): List<Pair<String, String>> = if (!withHistory) {
            emptyList()
        } else {
            _state.value.entries.dropLast(if (requestShown) 1 else 0).filter { it.speaker != Speaker.System }.takeLast(8)
                .map { (if (it.speaker == Speaker.User) "user" else "assistant") to it.text }
        }

        override fun schedule(request: TaskRequest.Scheduled): String =
            "Scheduled ${TaskReplies.describe(this@AssistantController.schedule(request), now())}."
    }

    // ------------------------------------------------------------------ confirmations

    private suspend fun askConfirmation(request: ConfirmationRequest): Boolean {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm = deferred
        val previousPhase = _state.value.phase
        _state.update { it.copy(pendingConfirmation = PendingConfirmation(id, request.question, request.detail)) }
        notifications.showConfirmation(id, request.question, request.detail)
        return try {
            if (settingsRepo.current.speakReplies) {
                if (session == null) _state.update { it.copy(phase = AssistantPhase.Speaking) }
                try {
                    speaker.speak(request.question + " Say yes or no, or tap Approve.", null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The question is also on screen and in a notification; carry on without speech.
                }
            }
            // Voice answer is possible only while the microphone service is running.
            if (_state.value.wakeServiceRunning && !deferred.isCompleted && session == null) {
                confirmSession = startListening(InputSource.Mic, null, deferred)
            }
            if (session == null) _state.update { it.copy(phase = AssistantPhase.Acting) }
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            withContext(NonCancellable) {
                // Answered by tap or notification: close the mic opened for a spoken answer.
                confirmSession?.let { if (session === it) cancelListening() }
                confirmSession = null
                pendingConfirm = null
                notifications.cancelConfirmation(id)
                _state.update {
                    val phase = when {
                        session != null -> AssistantPhase.Hearing
                        previousPhase == AssistantPhase.Hearing -> busyPhase(it)
                        else -> previousPhase
                    }
                    it.copy(pendingConfirmation = null, phase = phase)
                }
            }
        }
    }

    /** From the in-app dialog or a notification action. */
    fun answerConfirmation(id: Long, approved: Boolean) {
        if (_state.value.pendingConfirmation?.id != id) return
        speaker.stop()
        pendingConfirm?.complete(approved)
    }

    private fun answerByVoice(answer: String, confirmation: CompletableDeferred<Boolean>) {
        if (isStopPhrase(answer)) {
            stop()
            return
        }
        val normalized = answer.lowercase().trim(' ', '.', '!', '?', '।')
        val verdict = when {
            NO_WORDS.any { normalized == it || normalized.startsWith("$it ") } -> false
            YES_WORDS.any { normalized == it || normalized.startsWith("$it ") } -> true
            else -> null
        }
        if (verdict != null) confirmation.complete(verdict)
        else setStatus("Didn't understand “$answer”. Tap Approve or Deny.")
    }

    // ------------------------------------------------------------------ stop

    /** The Stop control: cancels recording, model requests, actions, speech and queued tasks immediately. */
    fun stop(silent: Boolean = false) {
        cancelListening()
        cancelWork()
        harness.clearQueue()
        silenceAlarms()
        listenWhenReady?.cancel()
        listenWhenReady = null
        activeTaskId = null
        _state.update {
            it.copy(
                phase = restingPhase(), currentAction = null, liveTranscript = "", pushToTalkActive = false, taskRunning = false,
                statusMessage = if (silent) it.statusMessage else "Stopped.", statusIsError = false,
            )
        }
    }

    private fun cancelWork() {
        // Unfinished work gets a visible end, so the conversation (which the model sees) never
        // leaves a request hanging that it might pick up again with the next one.
        if (taskJob?.isActive == true && activeTaskId != null) addEntry(Speaker.Wakey, AgentPrompt.STOPPED)
        pendingConfirm?.complete(false)
        taskJob?.cancel()
        taskJob = null
        interruptSpeech()
    }

    /** Silences Wakey without cancelling a running task (barge-in, voice preview). */
    private fun interruptSpeech() {
        speechJob?.cancel()
        speechJob = null
        speaker.stop()
    }

    // ------------------------------------------------------------------ settings helpers for the UI

    fun updateSettings(transform: (WakeySettings) -> WakeySettings) = settingsRepo.update(transform)

    fun saveApiKey(kind: SecretKind, value: String) = secrets.set(kind, value)
    fun hasApiKey(kind: SecretKind): Boolean = secrets.has(kind)
    fun apiKeyHint(kind: SecretKind): String? = secrets.hint(kind)

    /** Converts a candidate wake phrase to model tokens so Settings can show and validate it. */
    fun previewWakePhrase(phrase: String): Result<EncodedKeyword> = runCatching {
        val encoder = synchronized(this) {
            keywordEncoder ?: KeywordEncoder.fromAssets(appContext).also { keywordEncoder = it }
        }
        encoder.encode(WakeySettings.normalizeWakePhrase(phrase))
    }

    suspend fun testLlmConnection(): String = try {
        chatModel.testConnection()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Failed: ${e.message}"
    }

    suspend fun testJevConnection(): String = try {
        decisionModel.testConnection()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Failed: ${e.message}"
    }

    suspend fun testDeepgramConnection(): String = try {
        (stt as? ai.wakey.android.stt.DeepgramFluxStt)?.testConnection() ?: "Not available"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Failed: ${e.message}"
    }

    fun androidVoices(): List<VoiceOption> = speaker.android.availableVoices()
    fun deepgramVoices(): List<VoiceOption> = ai.wakey.android.tts.DeepgramSpeaker.VOICES

    fun previewVoice(sample: String = "Hi, I'm Wakey. Namaste! Kya madad karun?") {
        interruptSpeech()
        // A running task keeps its phase on screen.
        val ownsPhase = taskJob?.isActive != true
        speechJob = scope.launch {
            if (ownsPhase) _state.update { it.copy(phase = AssistantPhase.Speaking) }
            try {
                speaker.speak(sample, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Voice preview failed: ${e.message}", error = true)
            } finally {
                if (ownsPhase) _state.update { it.copy(phase = restingPhase()) }
            }
        }
    }

    fun clearConversation() = _state.update {
        it.copy(entries = emptyList(), recentActions = emptyList(), lastTimings = null, taskEntryId = null)
    }

    fun dismissStatus() = _state.update { it.copy(statusMessage = null, statusIsError = false) }

    // ------------------------------------------------------------------ internals

    private fun restingPhase(s: AssistantUiState = _state.value) =
        if (s.wakeWordEnabled) AssistantPhase.WakeListening else AssistantPhase.Idle

    /** The phase to return to after listening or speaking: the running task's, else resting. */
    private fun busyPhase(s: AssistantUiState): AssistantPhase = when {
        !s.taskRunning -> restingPhase(s)
        s.currentAction != null -> AssistantPhase.Acting
        else -> AssistantPhase.Thinking
    }

    private fun addEntry(speaker: Speaker, text: String, source: InputSource? = null, isError: Boolean = false): Long {
        val id = ids.getAndIncrement()
        _state.update {
            it.copy(entries = (it.entries + ChatEntry(id, speaker, text, System.currentTimeMillis(), source, null, isError)).takeLast(MAX_ENTRIES))
        }
        return id
    }

    private fun setStatus(message: String?, error: Boolean = false) =
        _state.update { it.copy(statusMessage = message, statusIsError = error) }

    /** Shows a problem, and speaks it with the (offline-capable) voice when useful. */
    private fun reportProblem(message: String, speak: Boolean = true) {
        setStatus(message, error = true)
        _state.update { it.copy(phase = restingPhase()) }
        if (speak && settingsRepo.current.speakReplies) {
            speechJob?.cancel()
            speechJob = scope.launch { runCatching { speaker.android.speak(message, "en-IN") } }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Biases Flux toward the wake phrase and the words simple commands depend on. */
    private fun keyterms(s: WakeySettings): List<String> =
        (listOf("Wakey") + s.spokenWake.split(' ').filter { it.length > 3 } + COMMAND_KEYTERMS).distinct().take(MAX_KEYTERMS)

    private fun describe(command: FastCommand) = when (command) {
        is FastCommand.Torch -> if (command.on) "Turning the flashlight on" else "Turning the flashlight off"
        is FastCommand.OpenApp -> "Opening ${command.appName}"
        FastCommand.GoHome -> "Going to the home screen"
        FastCommand.GoBack -> "Going back"
    }

    /** Mutable timing accumulator for one request. */
    private class TurnClock(val source: InputSource, val startedAt: Long, val wakeDetectionMs: Long?) {
        var sttConnectMs: Long? = null
        var transcriptionMs: Long? = null
        var speechSessionMs: Long? = null
        var speechStarted = false
        var lastEventAt: Long = startedAt
        var requestAt: Long = startedAt
        /** How long before [requestAt] the agent started on Flux's likely end of turn. */
        var headStartMs: Long? = null
        var firstActionAt: Long? = null
        var replyStartAt: Long? = null
        var doneAt: Long? = null
        var route = ""
        var steps = 0
        var llmCalls = 0
        var decisionCalls = 0
        var timeline: List<ai.wakey.android.agent.StepTiming> = emptyList()
        var promptTokens = 0
        var completionTokens = 0

        fun toTimings() = TurnTimings(
            source = source,
            wakeDetectionMs = wakeDetectionMs,
            sttConnectMs = sttConnectMs,
            transcriptionMs = transcriptionMs,
            speechSessionMs = speechSessionMs,
            headStartMs = headStartMs,
            firstActionMs = firstActionAt?.let { it - requestAt },
            spokenReplyMs = replyStartAt?.let { it - requestAt },
            totalMs = doneAt?.let { it - requestAt },
            route = route,
            steps = steps,
            llmCalls = llmCalls,
            decisionCalls = decisionCalls,
            timeline = timeline,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        )
    }

    companion object {
        private const val NO_SPEECH_TIMEOUT_MS = 7_000L
        /** An unsure wake detection must be confirmed by a transcript within this long of it. */
        private const val CHECK_TIMEOUT_MS = 5_000L
        private const val HEY_CHECK_TIMEOUT_MS = 7_000L

        /** Stripped from "hey" requests; people used to the old phrase may still say "Hey Wakey". */
        private const val HEY_STRIP = "Hey Wakey"
        private const val NO_CHECK = -1L

        /** The wake chime and its echo, which the mic boost shouldn't learn from. */
        private const val CHIME_HOLD_MS = 500L

        private const val FLOATING_BUTTON_ON = "Floating button on. You'll see it over your other apps, not inside Wakey."
        private const val MAX_HEARD_CHARS = 80
        internal const val MIC_MUTED_MESSAGE =
            "Android is muting Wakey's microphone. Turn on “Microphone access” in quick settings; " +
                "if it is on, open Wakey once so Android lets it listen in the background."
        private const val MAX_UTTERANCE_MS = 22_000L
        private const val MAX_PUSH_TO_TALK_MS = 30_000L
        /** Flux ends a turn within eot_timeout (3 s) of silence, so this long without events means a dead link. */
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val WATCHDOG_TICK_MS = 250L
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        /** How long the floating button waits for the voice service before opening Wakey instead. */
        private const val VOICE_SERVICE_START_TIMEOUT_MS = 3_000L
        private const val SNOOZE_MS = 10 * 60_000L
        private const val MAX_ENTRIES = 200
        private const val MAX_RECENT_ACTIONS = WakeySettings.MAX_AGENT_STEPS_LIMIT
        private const val MAX_KEYTERMS = 16
        private val COMMAND_KEYTERMS = listOf(
            "flashlight", "torch", "Calculator", "YouTube", "WhatsApp", "Chrome", "Settings", "Bluetooth", "remind", "alarm",
            "kholo", "karo", "jalao", "band karo",
        )
        private val PASSIVE_TOOLS = setOf("read_screen", "take_screenshot", "finish", "ask_user", "thinking")

        private val STOP_PHRASES = setOf(
            "stop", "cancel", "stop it", "never mind", "nevermind", "bas", "ruko", "ruk jao", "band karo",
            "रुको", "बस", "बंद करो", "रुक जाओ",
        )
        private val YES_WORDS = listOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "confirm", "go ahead", "do it", "send it", "haan", "han", "ha",
            "ji", "ji haan", "theek hai", "thik hai", "हाँ", "हां", "जी", "ठीक है",
        )
        private val NO_WORDS = listOf(
            "no", "nope", "don't", "do not", "cancel", "stop", "nahi", "nahin", "mat karo", "नहीं", "मत करो", "रुको",
        )

        /** Whether two transcripts say the same words, whatever their case and punctuation. */
        internal fun sameWords(a: String, b: String): Boolean = words(a) == words(b)

        private fun words(text: String) = text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }

        private val NOT_WORD = Regex("[^\\p{L}\\p{N}\\p{M}]+")

        internal fun isStopPhrase(text: String): Boolean =
            text.lowercase().trim(' ', '.', '!', '?', '।', ',') in STOP_PHRASES

        /** Removes a leading wake phrase ("Hey Wakey, …") that the pre-roll audio may include. */
        internal fun stripWakePhrase(text: String, wakePhrase: String): String = WakeTranscript.strip(text, wakePhrase)

        internal fun languageTagFor(reply: String, languages: List<String>): String? = when {
            reply.any { it in 'ऀ'..'ॿ' } -> "hi-IN"
            languages.firstOrNull() == "hi" -> "en-IN" // romanised Hinglish: Indian English voice
            else -> null
        }
    }
}
