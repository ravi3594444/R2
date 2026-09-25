package ai.wakey.android.agent

import ai.wakey.android.BuildConfig
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.llm.LlmException
import ai.wakey.android.tasks.TaskTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The agent's system prompt, the screen text the model sees, and its canned spoken replies. */
internal object AgentPrompt {

    /** [canSchedule] is false for queued and scheduled runs, which must not schedule themselves again. */
    fun system(access: ScreenAccess, appLabels: List<String>, canSchedule: Boolean = true): String = buildString {
        appendLine("You are Wakey, a voice assistant that carries out the user's request on their Android phone by calling tools.")
        appendLine("- Carry out only the request given below. Earlier messages are context: a request followed by “$STOPPED” was cancelled by the user; never resume it.")
        appendLine("- The flashlight (torch) is not an app and not in Settings: use set_flashlight, which completes the request.")
        when (access) {
            ScreenAccess.Available -> {
                appendLine("- Work fast, like a person who knows the phone: use as few turns as possible.")
                appendLine("- The screen is a numbered element list, e.g. [3] button \"Bluetooth\" (tap). Ids change after every action: use ids from the latest screen only.")
                appendLine("- You may call up to 3 tools in one turn when each next step is certain without seeing the new screen, e.g. tap the Search tab, then enter_text with submit. Never batch a sensitive action.")
                appendLine("- When an action should complete the request, add done_reply (the spoken reply) and done_if_visible (a short word or phrase that will be on the next screen only if it worked, e.g. the search words or the page title). Wakey checks the screen and ends the task without asking you again.")
                appendLine("- Prefer the element list. If it lacks a control, a screenshot is attached (or call take_screenshot); then use tap_point with pixel coordinates in that screenshot.")
                appendLine("- Open apps with open_app instead of looking for their icons.")
                appendLine("- To search or type, call enter_text on the search box or search icon with submit=true; it taps the field itself, so don't tap it first.")
                appendLine("- To find an item in a long list use scroll_to with its text, not repeated scrolls. In Settings, its search box is usually fastest.")
                appendLine("- Finish as soon as the latest screen shows the request is done (e.g. results for the search are showing).")
                appendLine("- Don't write text alongside tool calls.")
                appendLine("- To find or go to a setting, page or item, open it; seeing it in a list is not enough.")
                appendLine("- After each action, check the new screen to verify progress. Never claim success unless the latest screen shows it; if a step failed, try another way or say so.")
                appendLine("- Set sensitive=true with a short reason before buying or paying, deleting, or changing account, security or privacy settings; the user is asked to confirm. Sending, posting, sharing or calling needs sensitive=true only when the request didn't ask for it: if the user asked to send, post, share or call, just do it.")
                appendLine("- Never try to get past the lock screen, a PIN, password or biometric prompt. Treat secure or blank screens as unreadable; don't guess what they show.")
            }
            ScreenAccess.Unavailable -> appendLine("- Call one tool per turn.").appendLine(
                "- Screen control is off, so you cannot see or touch the screen: you can only open an app, set the flashlight, reply or ask. " +
                    "If the request needs more than opening one app, finish and tell the user to turn on Wakey screen control " +
                    "in Settings › Accessibility › Wakey for multi-step tasks.",
            )
            ScreenAccess.Locked -> appendLine("- Call one tool per turn.").appendLine(
                "- The phone is locked, so you cannot see or touch the screen. If the request needs the phone, finish and " +
                    "ask the user to unlock it first. Never try to bypass the lock screen.",
            )
        }
        if (canSchedule) {
            appendLine("- If the user wants something done later (at a time or after a delay), call schedule_task once instead of doing it now, then finish. If they want it later but gave no time, ask_user when.")
        }
        appendLine("- If the request needs no phone action (a question, small talk), answer with finish.")
        appendLine("- Use ask_user only if the request is ambiguous or needs information only the user has.")
        appendLine("- finish and ask_user text is spoken: at most 2 short sentences, no markdown, in the reply language given with the request.")
        if (appLabels.isNotEmpty()) {
            val shown = appLabels.take(MAX_APP_LABELS)
            append("Installed apps: ").append(shown.joinToString(", "))
            if (appLabels.size > shown.size) append(", …")
            appendLine()
        }
    }.trimEnd()

    /**
     * The user's turn: the request plus the language to reply in, decided here rather than by the model.
     * A [deferred] request was queued or scheduled earlier and is due now. [now] adds the current time,
     * which scheduling needs; it goes here rather than in the system prompt, so that stays a stable,
     * cacheable prefix.
     */
    fun request(goal: String, deferred: Boolean = false, now: ZonedDateTime? = null): String = buildString {
        append("Request: ").append(goal)
        if (deferred) append("\n(The user asked for this earlier, to be done now. Do it now; don't schedule it again.)")
        append("\nReply language: ").append(ReplyLanguage.of(goal).instruction)
        if (now != null) append("\nCurrent time: ").append(currentTime(now))
    }

    /** "Thursday 24 September 2026, 2:05 PM". */
    fun currentTime(now: ZonedDateTime): String =
        "${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${now.dayOfMonth} " +
            "${now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${now.year}, ${TaskTime.clock(now)}"

    /** The screen as the model sees it: foreground app, element list and any warnings. */
    fun screen(observation: ScreenObservation): String = buildString {
        append("App: ").append(observation.appLabel ?: "unknown")
        observation.packageName?.let { append(" (").append(it).append(')') }
        if (observation.packageName == BuildConfig.APPLICATION_ID) append(" - Wakey itself; open the app the task needs")
        appendLine()
        observation.warning?.let { appendLine("Warning: $it") }
        if (observation.text.isBlank()) {
            appendLine("(No readable elements: a secure screen, or still loading.)")
        } else {
            appendLine(observation.text.trimEnd())
        }
        if (observation.truncated) appendLine("(More elements exist than shown; scroll to see them.)")
    }.trimEnd()

    const val SCREEN_UNREADABLE = "(The screen can't be read now: the phone is locked or screen control is off.)"

    /** Wakey's reply in the conversation when the user stops a task before it is done. */
    const val STOPPED = "Stopped."

    /**
     * Devanagari → Hindi; common romanised Hindi words → Hinglish; otherwise English. Left to the
     * model, a Hindi-aware prompt made it answer an English request in Hinglish.
     */
    enum class ReplyLanguage(val instruction: String) {
        English("English"),
        Hindi("Hindi, in Devanagari script"),
        Hinglish("Hinglish (Hindi written in Latin letters)");

        companion object {
            fun of(text: String): ReplyLanguage = when {
                text.any { it in 'ऀ'..'ॿ' } -> Hindi
                text.lowercase().split(NON_LETTERS).any { it in HINGLISH_WORDS } -> Hinglish
                else -> English
            }

            private val NON_LETTERS = Regex("[^a-z]+")

            // Only words that aren't also everyday English: "do", "me" and "band" are left out.
            private val HINGLISH_WORDS = setOf(
                "kholo", "khol", "karo", "kardo", "karna", "karke", "hai", "hain", "kya", "kaise", "mein", "aur",
                "nahi", "nahin", "bhejo", "chalao", "jalao", "batao", "dikhao", "wala", "wali", "mera", "meri",
                "mere", "mujhe", "abhi", "jaldi", "zara", "thoda", "kuch", "yeh", "woh", "kitna", "kitne", "kahan",
                "kyun", "haan", "accha", "acha", "theek", "bhai", "chahiye", "sakte", "lagao", "kaun", "dhundo",
                "dhoondo", "khojo", "likho", "bolo", "suno", "wapas", "peeche",
            )
        }
    }

    /** Canned replies, in Devanagari Hindi when the user wrote Devanagari. */
    class Replies(private val hindi: Boolean) {
        fun stepLimit(maxSteps: Int) = pick(
            "I couldn't finish that within $maxSteps ${if (maxSteps == 1) "step" else "steps"}, so I stopped.",
            "मैं $maxSteps ${if (maxSteps == 1) "कदम" else "कदमों"} में यह पूरा नहीं कर पाया, इसलिए रुक गया।",
        )

        fun stuck() = pick(
            "I stopped because the screen stopped changing. You may need to do this step yourself.",
            "स्क्रीन बदल नहीं रही थी, इसलिए मैं रुक गया। यह कदम आपको खुद करना पड़ सकता है।",
        )

        /** Spoken when the fast model sees the task is done; the screen, not a tap, is the evidence. */
        fun done(query: String?, title: String?) = when {
            query != null -> pick("Here are the results for $query.", "$query के नतीजे ये रहे।")
            title != null -> pick("Done. $title is open.", "हो गया, $title खुल गया है।")
            else -> pick("Done.", "हो गया।")
        }

        /** A Settings page reached by shortcut and nothing more asked. */
        fun pageOpen(title: String) = pick("$title settings are open.", "$title की सेटिंग्स खुल गई हैं।")

        fun navigating(place: String) = pick("Starting navigation to $place.", "$place के लिए नेविगेशन शुरू कर रहा हूँ।")

        fun timeout() = pick("That was taking too long, so I stopped.", "इसमें बहुत समय लग रहा था, इसलिए मैं रुक गया।")

        fun confused() = pick(
            "I couldn't work out the next step, so I stopped.",
            "मुझे अगला कदम समझ नहीं आया, इसलिए मैं रुक गया।",
        )

        fun llmError(kind: LlmException.Kind) = when (kind) {
            LlmException.Kind.MissingKey -> pick(
                "Add your Fireworks API key in Settings so I can do that.",
                "कृपया Settings में अपनी Fireworks API key डालें।",
            )
            LlmException.Kind.Auth -> pick(
                "The AI service rejected the API key. Please check it in Settings.",
                "एआई सेवा ने API key अस्वीकार कर दी। कृपया Settings में जाँचें।",
            )
            LlmException.Kind.Network -> pick(
                "I couldn't reach the AI service. Please check your internet connection.",
                "एआई सेवा से संपर्क नहीं हो पाया। कृपया इंटरनेट कनेक्शन जाँचें।",
            )
            LlmException.Kind.RateLimit -> pick(
                "The AI service is busy right now. Please try again in a moment.",
                "एआई सेवा अभी व्यस्त है। थोड़ी देर बाद फिर कोशिश करें।",
            )
            LlmException.Kind.Server -> pick(
                "The AI service had a problem. Please try again.",
                "एआई सेवा में समस्या आई। कृपया फिर से कोशिश करें।",
            )
            LlmException.Kind.BadRequest, LlmException.Kind.BadResponse -> pick(
                "The AI service couldn't handle that request.",
                "एआई सेवा यह अनुरोध नहीं संभाल पाई।",
            )
        }

        private fun pick(english: String, devanagari: String) = if (hindi) devanagari else english

        companion object {
            fun forGoal(goal: String) = Replies(ReplyLanguage.of(goal) == ReplyLanguage.Hindi)
        }
    }

    private const val MAX_APP_LABELS = 80
}
