package ai.wakey.android.core

import ai.wakey.android.agent.AgentListener
import ai.wakey.android.agent.AgentLoop
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
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.service.Notifications
import ai.wakey.android.service.WakeService
import ai.wakey.android.stt.SpeechToText
import ai.wakey.android.stt.SttConfig
import ai.wakey.android.stt.SttError
import ai.wakey.android.stt.SttListener
import ai.wakey.android.stt.SttSession
import ai.wakey.android.tts.RoutingSpeaker
import ai.wakey.android.tts.VoiceOption
import ai.wakey.android.wake.EncodedKeyword
import ai.wakey.android.wake.KeywordEncoder
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The single pipeline every request goes through, whether it starts from the wake word, the mic
 * button, push-to-talk or the text box:
 *
 *   wake/mic → Deepgram stream → transcript → fast Android command | agent loop → reply → TTS
 *
 * All state changes happen on the main thread. [stop] cancels recording, model requests, actions
 * and speech at once.
 */
class AssistantController(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val audio: AudioEngine,
    private val stt: SpeechToText,
    private val speaker: RoutingSpeaker,
    private val chatModel: ChatModel,
    private val device: DeviceActions,
    private val agent: AgentLoop,
    private val notifications: Notifications,
    private val chime: WakeChime = WakeChime(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()
    val settings: StateFlow<WakeySettings> get() = settingsRepo.settings

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

    /** [sessionToken] of the session confirming an unsure wake detection, until it is confirmed. */
    private var checkToken = NO_CHECK
    private val _wakeStats = MutableStateFlow(WakeStats())
    val wakeStats: StateFlow<WakeStats> = _wakeStats.asStateFlow()

    /** See [AudioEngine.micMuted]. */
    val micMuted: StateFlow<Boolean> get() = audio.micMuted
    @Volatile private var keywordEncoder: KeywordEncoder? = null

    init {
        scope.launch { audio.level.collect { level -> _state.update { it.copy(micLevel = level) } } }
        scope.launch {
            settingsRepo.settings.map { it.wakePhrase to it.wakeSensitivity }.distinctUntilChanged().drop(1)
                .collect { (phrase, sensitivity) ->
                    if (_state.value.wakeServiceRunning) {
                        runCatching { audio.updateWakePhrase(phrase, sensitivity) }
                            .onSuccess { setStatus("Now listening for “$phrase”.") }
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
        scope.launch { WakeService.startProblem.filterNotNull().collect { setStatus(it, error = true) } }
    }

    // ------------------------------------------------------------------ wake service lifecycle

    /** Called from the visible UI. Starting the foreground service from here satisfies Android 14+. */
    fun setWakeListening(context: Context, enabled: Boolean) {
        settingsRepo.update { it.copy(wakeListeningWanted = enabled) }
        if (enabled) WakeService.start(context) else WakeService.stop(context)
    }

    /**
     * Turns wake listening back on if the user left it on: when Wakey is opened, when its assistant
     * panel shows, and when Android rebinds Wakey as the default assistant after a reboot or after
     * its process was killed. A service that is already running is started again: done while Wakey
     * is visible, that renews its right to use the microphone in the background, which Android may
     * have withheld when the service last started (the microphone then records only silence).
     */
    fun restoreWakeListening(context: Context) {
        if (settingsRepo.current.wakeListeningWanted) WakeService.start(context)
    }

    /** Called by [WakeService] once it is in the foreground. Returns false if the mic could not start. */
    fun onWakeServiceStarted(): Boolean {
        val s = settingsRepo.current
        return try {
            audio.startWakeListening(s.wakePhrase, s.wakeSensitivity, ::onWakeDetected)
            _state.update {
                it.copy(
                    wakeServiceRunning = true,
                    phase = if (it.phase == AssistantPhase.Idle) AssistantPhase.WakeListening else it.phase,
                )
            }
            setStatus("Say “${s.wakePhrase}” followed by your request.")
            true
        } catch (e: Exception) {
            setStatus("Could not start wake listening: ${e.message}", error = true)
            false
        }
    }

    /** Called by [WakeService] when it stops for any reason. */
    fun onWakeServiceStopped() {
        audio.stopWakeListening()
        _state.update {
            it.copy(
                wakeServiceRunning = false,
                phase = if (it.phase == AssistantPhase.WakeListening) AssistantPhase.Idle else it.phase,
            )
        }
    }

    /** Notification "Turn off": stop everything and end the foreground service. */
    fun turnOff() {
        stop(silent = true)
        settingsRepo.update { it.copy(wakeListeningWanted = false) }
        WakeService.stop(appContext)
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
            // Barge-in: the wake word interrupts speech or a running task.
            if (taskJob?.isActive == true || speechJob?.isActive == true) cancelWork()
            playWakeSound()
            startListening(InputSource.WakeWord, event)
        }
    }

    private fun playWakeSound() {
        if (settingsRepo.current.wakeSound) chime.play()
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
        prewarmReplyVoice()
        _state.update { it.copy(phase = AssistantPhase.Hearing, liveTranscript = "", statusMessage = null, statusIsError = false) }
    }

    private fun prewarmReplyVoice() {
        val s = settingsRepo.current
        if (s.speakReplies && s.ttsEngine == TtsEngine.Deepgram) speaker.deepgram.prewarmConnection()
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
    fun onMicTap() {
        cancelCheck()
        if (session != null) {
            session?.endTurn()
            return
        }
        cancelWork()
        startListening(InputSource.Mic, null)
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
        cancelWork()
        startListening(InputSource.Assistant, null)
    }

    fun onPushToTalkPressed() {
        cancelCheck()
        if (session != null) return
        cancelWork()
        _state.update { it.copy(pushToTalkActive = true) }
        startListening(InputSource.PushToTalk, null)
    }

    fun onPushToTalkReleased() {
        _state.update { it.copy(pushToTalkActive = false) }
        session?.endTurn()
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
                        when (WakeTranscript.check(text, s.wakePhrase, isFinal)) {
                            WakeTranscript.Verdict.Undecided -> return@post
                            WakeTranscript.Verdict.NotHeard -> {
                                rejectCheck(text)
                                return@post
                            }
                            WakeTranscript.Verdict.Heard -> confirmCheck()
                        }
                    }
                    val cleaned = stripWakePhrase(text, s.wakePhrase)
                    if (!isFinal) {
                        if (cleaned.isNotBlank()) clock.speechStarted = true
                        _state.update { it.copy(liveTranscript = cleaned) }
                        return@post
                    }
                    clock.transcriptionMs = transcriptionMs
                    clock.speechSessionMs = SystemClock.elapsedRealtime() - clock.startedAt
                    finishListening()
                    when {
                        confirmation != null -> answerByVoice(cleaned, confirmation)
                        // Only "Hey Wakey" was heard (the user paused): keep listening for the request once.
                        cleaned.isBlank() && text.isNotBlank() && source == InputSource.WakeWord && !reopened ->
                            startListening(InputSource.WakeWord, wake, reopened = true)
                        cleaned.isBlank() -> reportProblem("I didn't catch that.", speak = source == InputSource.WakeWord)
                        else -> handleUtterance(cleaned, source, languages)
                    }
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
            stt.open(SttConfig(model = s.sttModel, languageHints = s.languageMode.hints, keyterms = keyterms(s)), listener)
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
        if (!check) prewarmReplyVoice()
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
                checkToken == sessionToken -> if (now - clock.startedAt > CHECK_TIMEOUT_MS) {
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

    /** Stop streaming audio; the STT session closes itself after a final transcript. */
    private fun finishListening() {
        checkToken = NO_CHECK
        listenWatchdog?.cancel()
        listenWatchdog = null
        session = null
        sessionToken++
        audio.stopCommandStream()
        _state.update {
            it.copy(liveTranscript = "", pushToTalkActive = false, phase = if (it.phase == AssistantPhase.Hearing) restingPhase() else it.phase)
        }
    }

    private fun cancelListening() {
        session?.cancel()
        finishListening()
    }

    // ------------------------------------------------------------------ text input

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        cancelWork()
        cancelListening()
        turn = TurnClock(InputSource.Text, SystemClock.elapsedRealtime(), null)
        handleUtterance(trimmed, InputSource.Text, emptyList())
    }

    // ------------------------------------------------------------------ processing

    private fun handleUtterance(text: String, source: InputSource, languages: List<String>) {
        val clock = turn ?: TurnClock(source, SystemClock.elapsedRealtime(), null)
        clock.requestAt = SystemClock.elapsedRealtime()
        addEntry(Speaker.User, text, source = source)
        if (isStopPhrase(text)) {
            stop()
            return
        }
        taskJob = scope.launch {
            var reply: String
            var isError = false
            try {
                _state.update { it.copy(phase = AssistantPhase.Thinking, recentActions = emptyList(), currentAction = null) }
                // A garbled app name ("u two colo") is better handled by the agent than a "no such app" reply.
                val fast = FastCommandRouter.route(text)?.takeUnless {
                    it is FastCommand.OpenApp && secrets.has(SecretKind.LlmApiKey) && !device.canOpen(it.appName)
                }
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
                    val result = agent.run(text, agentListener(clock))
                    clock.steps = result.steps
                    clock.llmCalls = result.llmCalls
                    clock.promptTokens = result.promptTokens
                    clock.completionTokens = result.completionTokens
                    result.firstActionAtMs?.let { clock.firstActionAt = it }
                    reply = result.reply
                    isError = result.status == AgentStatus.Failed || result.status == AgentStatus.Timeout
                    if (result.status == AgentStatus.Cancelled) return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                isError = true
            }
            val entryId = addEntry(Speaker.Wakey, reply, isError = isError)
            speakReply(reply, languages, clock, entryId)
        }
    }

    private suspend fun speakReply(reply: String, languages: List<String>, clock: TurnClock, entryId: Long) {
        val s = settingsRepo.current
        if (s.speakReplies && reply.isNotBlank()) {
            _state.update { it.copy(phase = AssistantPhase.Speaking) }
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
            st.copy(
                phase = restingPhase(),
                currentAction = null,
                lastTimings = timings,
                entries = st.entries.map { if (it.id == entryId) it.copy(timings = timings) else it },
            )
        }
    }

    private fun agentListener(clock: TurnClock) = object : AgentListener {
        override fun onAction(action: AgentActionInfo) {
            if (action.result == null && action.toolName !in PASSIVE_TOOLS && clock.firstActionAt == null) {
                clock.firstActionAt = SystemClock.elapsedRealtime()
            }
            _state.update { st ->
                val others = st.recentActions.filterNot { it.step == action.step && it.toolName == action.toolName }
                st.copy(
                    phase = if (action.result == null) AssistantPhase.Acting else AssistantPhase.Thinking,
                    currentAction = action,
                    recentActions = (others + action).takeLast(MAX_RECENT_ACTIONS),
                )
            }
        }

        override suspend fun confirm(request: ConfirmationRequest): Boolean = askConfirmation(request)

        override fun history(): List<Pair<String, String>> =
            _state.value.entries.dropLast(1).filter { it.speaker != Speaker.System }.takeLast(8)
                .map { (if (it.speaker == Speaker.User) "user" else "assistant") to it.text }
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
                _state.update { it.copy(phase = AssistantPhase.Speaking) }
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
                _state.update { it.copy(pendingConfirmation = null, phase = previousPhase) }
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

    /** The Stop control: cancels recording, model requests, actions and speech immediately. */
    fun stop(silent: Boolean = false) {
        cancelListening()
        cancelWork()
        _state.update {
            it.copy(
                phase = restingPhase(), currentAction = null, liveTranscript = "", pushToTalkActive = false,
                statusMessage = if (silent) it.statusMessage else "Stopped.", statusIsError = false,
            )
        }
    }

    private fun cancelWork() {
        pendingConfirm?.complete(false)
        taskJob?.cancel()
        taskJob = null
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
        cancelWork()
        speechJob = scope.launch {
            _state.update { it.copy(phase = AssistantPhase.Speaking) }
            try {
                speaker.speak(sample, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Voice preview failed: ${e.message}", error = true)
            } finally {
                _state.update { it.copy(phase = restingPhase()) }
            }
        }
    }

    fun clearConversation() = _state.update { it.copy(entries = emptyList(), recentActions = emptyList(), lastTimings = null) }

    fun dismissStatus() = _state.update { it.copy(statusMessage = null, statusIsError = false) }

    // ------------------------------------------------------------------ internals

    private fun restingPhase() = if (_state.value.wakeServiceRunning) AssistantPhase.WakeListening else AssistantPhase.Idle

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
        (listOf("Wakey") + s.wakePhrase.split(' ').filter { it.length > 3 } + COMMAND_KEYTERMS).distinct().take(MAX_KEYTERMS)

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
        var firstActionAt: Long? = null
        var replyStartAt: Long? = null
        var doneAt: Long? = null
        var route = ""
        var steps = 0
        var llmCalls = 0
        var promptTokens = 0
        var completionTokens = 0

        fun toTimings() = TurnTimings(
            source = source,
            wakeDetectionMs = wakeDetectionMs,
            sttConnectMs = sttConnectMs,
            transcriptionMs = transcriptionMs,
            speechSessionMs = speechSessionMs,
            firstActionMs = firstActionAt?.let { it - requestAt },
            spokenReplyMs = replyStartAt?.let { it - requestAt },
            totalMs = doneAt?.let { it - requestAt },
            route = route,
            steps = steps,
            llmCalls = llmCalls,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
        )
    }

    companion object {
        private const val NO_SPEECH_TIMEOUT_MS = 7_000L
        /** An unsure wake detection must be confirmed by a transcript within this long of it. */
        private const val CHECK_TIMEOUT_MS = 5_000L
        private const val NO_CHECK = -1L
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
        private const val MAX_ENTRIES = 200
        private const val MAX_RECENT_ACTIONS = 12
        private const val MAX_KEYTERMS = 16
        private val COMMAND_KEYTERMS = listOf(
            "flashlight", "torch", "Calculator", "YouTube", "WhatsApp", "Chrome", "Settings", "Bluetooth",
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
