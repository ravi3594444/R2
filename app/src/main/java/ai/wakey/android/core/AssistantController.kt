package ai.wakey.android.core

import ai.wakey.android.WakeyApp
import ai.wakey.android.agent.AgentListener
import ai.wakey.android.agent.AgentLoop
import ai.wakey.android.agent.AgentStatus
import ai.wakey.android.agent.ConfirmationRequest
import ai.wakey.android.agent.DeviceActions
import ai.wakey.android.agent.FastCommand
import ai.wakey.android.agent.FastCommandRouter
import ai.wakey.android.audio.AudioEngine
import ai.wakey.android.audio.WakeEvent
import ai.wakey.android.config.SecretKind
import ai.wakey.android.config.SecretStore
import ai.wakey.android.config.SettingsRepository
import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.ChatModel
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
    private val device: DeviceActions,
    private val agent: AgentLoop,
    private val notifications: Notifications,
    private val harness: TaskHarness,
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
    @Volatile private var keywordEncoder: KeywordEncoder? = null

    /** The wake word switch. The voice service runs while it or the floating button is on. */
    private var wakeWordWanted = false

    /** Set while the floating button waits for the voice service to start; times out into opening Wakey. */
    private var listenWhenReady: Job? = null

    /** Alarm notifications that may still ring, with when they started (elapsed realtime). */
    private val ringing = mutableMapOf<Long, Long>()

    init {
        scope.launch { audio.level.collect { level -> _state.update { it.copy(micLevel = level) } } }
        scope.launch {
            settingsRepo.settings.map { it.wakePhrase to it.wakeSensitivity }.distinctUntilChanged().drop(1)
                .collect { (phrase, sensitivity) ->
                    if (_state.value.wakeWordEnabled) {
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
        wakeWordWanted = enabled
        when {
            enabled && _state.value.wakeServiceRunning -> startWakeWord()
            enabled -> WakeService.start(context)
            else -> {
                stopWakeWord()
                if (!settingsRepo.current.floatingButton) WakeService.stop(context)
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
        when {
            enabled && !_state.value.wakeServiceRunning -> WakeService.start(context)
            !enabled && !wakeWordWanted -> WakeService.stop(context)
        }
    }

    /** Called by [WakeService] once it is in the foreground. Returns false if it has nothing to do. */
    fun onWakeServiceStarted(): Boolean {
        _state.update { it.copy(wakeServiceRunning = true) }
        val wakeWordFailed = wakeWordWanted && !startWakeWord()
        val buttonWaiting = listenWhenReady != null
        if (wakeWordFailed && !settingsRepo.current.floatingButton && !buttonWaiting) {
            _state.update { it.copy(wakeServiceRunning = false) }
            return false
        }
        if (buttonWaiting) {
            listenWhenReady?.cancel()
            listenWhenReady = null
            listen(InputSource.Button)
        }
        return true
    }

    /** Called by [WakeService] when it stops for any reason. */
    fun onWakeServiceStopped() {
        wakeWordWanted = false
        stopWakeWord()
        _state.update { it.copy(wakeServiceRunning = false) }
    }

    /** Notification "Turn off": stop everything, hide the floating button and end the voice service. */
    fun turnOff() {
        stop(silent = true)
        wakeWordWanted = false
        settingsRepo.update { it.copy(floatingButton = false) }
        WakeService.stop(appContext)
    }

    private fun startWakeWord(): Boolean {
        val s = settingsRepo.current
        return try {
            audio.startWakeListening(s.wakePhrase, s.wakeSensitivity, ::onWakeDetected)
            _state.update {
                it.copy(wakeWordEnabled = true, phase = if (it.phase == AssistantPhase.Idle) AssistantPhase.WakeListening else it.phase)
            }
            setStatus("Say “${s.wakePhrase}” followed by your request.")
            true
        } catch (e: Exception) {
            wakeWordWanted = false
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
            val confirmation = pendingConfirm
            if (confirmation != null) {
                // "Hey Wakey, yes" answers the pending confirmation instead of starting a new task.
                speaker.stop()
                confirmSession = startListening(InputSource.WakeWord, event, confirmation)
                return@launch
            }
            // Barge-in silences Wakey, but a running task keeps going until the request is heard:
            // "… after this" queues behind it, anything else replaces it.
            interruptSpeech()
            startListening(InputSource.WakeWord, event)
        }
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

    fun onPushToTalkPressed() {
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
     * pending approval instead of becoming a new request. Returns the session, or null if none started.
     */
    private fun startListening(
        source: InputSource,
        wake: WakeEvent?,
        confirmation: CompletableDeferred<Boolean>? = null,
        reopened: Boolean = false,
    ): SttSession? {
        // Never leave an earlier session streaming behind the new one.
        if (session != null) cancelListening()
        val s = settingsRepo.current
        if (!secrets.has(SecretKind.DeepgramApiKey)) {
            audio.stopCommandStream()
            reportProblem("Add your Deepgram API key in Settings to use voice.")
            return null
        }
        if (!isOnline()) {
            audio.stopCommandStream()
            reportProblem("No internet connection. Voice needs Deepgram; typed direct commands still work.")
            return null
        }
        val clock = TurnClock(source, SystemClock.elapsedRealtime(), wake?.detectionLatencyMs)
        if (confirmation == null) turn = clock
        val token = ++sessionToken
        _state.update {
            it.copy(phase = AssistantPhase.Hearing, liveTranscript = "", statusMessage = null, statusIsError = false)
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
            audio.stopCommandStream()
            reportProblem("Could not start speech recognition: ${e.message}")
            return null
        }
        session = newSession
        try {
            audio.startCommandStream { buffer, length -> newSession.sendPcm(buffer, length) }
        } catch (e: Exception) {
            cancelListening()
            reportProblem("Could not use the microphone: ${e.message}")
            return null
        }
        if (s.speakReplies && s.ttsEngine == TtsEngine.Deepgram) speaker.deepgram.prewarmConnection()
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
        listenWatchdog?.cancel()
        listenWatchdog = null
        session = null
        sessionToken++
        audio.stopCommandStream()
        _state.update {
            it.copy(liveTranscript = "", pushToTalkActive = false, phase = if (it.phase == AssistantPhase.Hearing) restingPhase() else it.phase)
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
        addEntry(Speaker.User, text, source = source)
        if (isStopPhrase(text)) {
            stop()
            return
        }
        val busy = taskJob?.isActive == true
        val time = now()
        when (val request = TaskParser.parse(text, time)) {
            is TaskRequest.Now -> {
                if (busy) cancelWork()
                runTask(harness.start(request.text), languages, clock)
            }
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
            is TaskRequest.CancelScheduled -> acknowledge(cancelScheduled(request, time), languages, clock)
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
        val job = scope.launch {
            var reply: String
            var isError = false
            try {
                _state.update {
                    it.copy(phase = AssistantPhase.Thinking, recentActions = emptyList(), currentAction = null, taskEntryId = requestEntry)
                }
                val fast = FastCommandRouter.route(task.text)
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
                    val result = agent.run(task.text, agentListener(clock, withHistory = source != InputSource.Scheduled), deferred)
                    clock.steps = result.steps
                    clock.llmCalls = result.llmCalls
                    clock.promptTokens = result.promptTokens
                    clock.completionTokens = result.completionTokens
                    result.firstActionAtMs?.let { clock.firstActionAt = it }
                    reply = result.reply
                    isError = result.status == AgentStatus.Failed || result.status == AgentStatus.Timeout
                    if (result.status == AgentStatus.Cancelled) {
                        harness.finish(task.id, TaskStatus.Cancelled, null)
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                harness.finish(task.id, TaskStatus.Cancelled, null)
                throw e
            } catch (e: Exception) {
                reply = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                isError = true
            }
            harness.finish(task.id, if (isError) TaskStatus.Failed else TaskStatus.Done, reply)
            val entryId = addEntry(Speaker.Wakey, reply, isError = isError)
            // Nobody asked just now, so the reply may go unheard.
            if (source == InputSource.Scheduled && !WakeyApp.isVisible) notifications.showTaskResult(task, reply, isError)
            speakReply(reply, languages, clock, entryId)
        }
        taskJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (taskJob === job) taskJob = null
                runNextIfIdle()
            }
        }
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
        runNextIfIdle()
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

    /** Cancels one task. The running one is skipped, and the queue moves on. */
    fun cancelTask(id: Long) {
        silenceAlarm(id)
        notifications.cancelTask(id)
        if (harness.board.value.running?.id == id && taskJob?.isActive == true) {
            cancelWork()
            _state.update { it.copy(phase = restingPhase(), currentAction = null) }
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
        val cutoff = SystemClock.elapsedRealtime() - ALARM_RING_MS
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
            if (ownsPhase) _state.update { it.copy(phase = AssistantPhase.Speaking) }
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
            if (ownsPhase) timed.copy(phase = restingPhase(), currentAction = null) else timed
        }
    }

    private fun agentListener(clock: TurnClock, withHistory: Boolean) = object : AgentListener {
        override fun onAction(action: AgentActionInfo) {
            if (action.result == null && action.toolName !in PASSIVE_TOOLS && clock.firstActionAt == null) {
                clock.firstActionAt = SystemClock.elapsedRealtime()
            }
            _state.update { st ->
                val others = st.recentActions.filterNot { it.step == action.step && it.toolName == action.toolName }
                st.copy(
                    // Once acting, stay acting between steps: the step list shows the thinking and the orb doesn't flicker.
                    phase = if (action.result == null || st.phase == AssistantPhase.Acting) AssistantPhase.Acting else AssistantPhase.Thinking,
                    currentAction = action,
                    recentActions = (others + action).takeLast(MAX_RECENT_ACTIONS),
                )
            }
        }

        override suspend fun confirm(request: ConfirmationRequest): Boolean = askConfirmation(request)

        override fun history(): List<Pair<String, String>> = if (!withHistory) {
            emptyList()
        } else {
            _state.value.entries.dropLast(1).filter { it.speaker != Speaker.System }.takeLast(8)
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

    /** The Stop control: cancels recording, model requests, actions, speech and queued tasks immediately. */
    fun stop(silent: Boolean = false) {
        cancelListening()
        cancelWork()
        harness.clearQueue()
        silenceAlarms()
        listenWhenReady?.cancel()
        listenWhenReady = null
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

    private fun restingPhase() = if (_state.value.wakeWordEnabled) AssistantPhase.WakeListening else AssistantPhase.Idle

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
        private const val MAX_UTTERANCE_MS = 22_000L
        private const val MAX_PUSH_TO_TALK_MS = 30_000L
        /** Flux ends a turn within eot_timeout (3 s) of silence, so this long without events means a dead link. */
        private const val STALL_TIMEOUT_MS = 8_000L
        private const val WATCHDOG_TICK_MS = 250L
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        /** How long the floating button waits for the voice service before opening Wakey instead. */
        private const val VOICE_SERVICE_START_TIMEOUT_MS = 3_000L
        private const val SNOOZE_MS = 10 * 60_000L
        /** Matches the alarm notification's timeout. */
        private const val ALARM_RING_MS = 10 * 60_000L
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

        internal fun isStopPhrase(text: String): Boolean =
            text.lowercase().trim(' ', '.', '!', '?', '।', ',') in STOP_PHRASES

        /** Removes a leading wake phrase ("Hey Wakey, …") that the pre-roll audio may include. */
        internal fun stripWakePhrase(text: String, wakePhrase: String): String {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val wakeWords = wakePhrase.lowercase().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || wakeWords.isEmpty()) return text.trim()
            fun norm(w: String) = w.lowercase().trim(',', '.', '!', '?', '।', ':', ';')
            var i = 0
            var matched = 0
            // Allow the transcript to start mid-phrase (e.g. only "Wakey," survived the pre-roll).
            while (i < words.size && matched < wakeWords.size) {
                val w = norm(words[i])
                val idx = wakeWords.indexOfFirst { similar(it, w) }
                if (idx < 0 || idx < matched) break
                matched = idx + 1
                i++
            }
            return if (i == 0) text.trim() else words.drop(i).joinToString(" ").trimStart(',', '.', ' ')
        }

        private fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length < 2 || b.length < 2) return false
            return levenshtein(a, b) <= if (a.length <= 3) 1 else 2
        }

        private fun levenshtein(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }

        internal fun languageTagFor(reply: String, languages: List<String>): String? = when {
            reply.any { it in 'ऀ'..'ॿ' } -> "hi-IN"
            languages.firstOrNull() == "hi" -> "en-IN" // romanised Hinglish: Indian English voice
            else -> null
        }
    }
}
