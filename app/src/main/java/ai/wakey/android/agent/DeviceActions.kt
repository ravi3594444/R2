package ai.wakey.android.agent

import ai.wakey.android.WakeyApp
import ai.wakey.android.accessibility.ScreenController
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Direct Android actions: torch, app launch, home/back. App launches go through [screen] when the
 * accessibility service is bound, because it may start activities while Wakey is in the background.
 */
class DeviceActions(
    private val context: Context,
    private val screen: () -> ScreenController?,
    private val appVisible: () -> Boolean = { WakeyApp.isVisible },
) {
    private val cameraManager: CameraManager? by lazy { context.getSystemService(CameraManager::class.java) }
    private val keyguard: KeyguardManager? by lazy { context.getSystemService(KeyguardManager::class.java) }

    @Volatile private var appCache: CachedApps? = null

    /** Runs a fast command. App launches are confirmed on screen when screen control is available. */
    suspend fun execute(command: FastCommand): ActionOutcome = when (command) {
        is FastCommand.Torch -> setTorch(command.on)
        is FastCommand.OpenApp -> openAndVerify(command.appName)
        FastCommand.GoHome -> navigate(home = true)
        FastCommand.GoBack -> navigate(home = false)
    }

    /** Works on the lock screen too; torch mode needs no CAMERA permission since API 23. */
    fun setTorch(on: Boolean): ActionOutcome {
        val manager = cameraManager ?: return NO_FLASHLIGHT
        return try {
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return NO_FLASHLIGHT
            manager.setTorchMode(cameraId, on)
            ActionOutcome(true, if (on) "Flashlight is on." else "Flashlight is off.")
        } catch (e: CameraAccessException) {
            val message = if (e.reason == CameraAccessException.CAMERA_DISABLED) {
                "The camera is disabled on this phone, so the flashlight can't be used."
            } else {
                "The flashlight is busy because another app is using the camera."
            }
            ActionOutcome(false, message)
        } catch (e: SecurityException) {
            ActionOutcome(false, "Android didn't let Wakey use the flashlight.")
        } catch (e: IllegalArgumentException) {
            NO_FLASHLIGHT
        }
    }

    /**
     * Starts the installed app that best matches [name]. The reply says "Opening …" because the
     * launch is not verified here; [execute] verifies it when screen control is on.
     */
    fun openApp(name: String): ActionOutcome = launch(name, resolve(name)).outcome

    /** Starts an activity, from the accessibility service when possible so it works from the background. */
    fun launchActivity(intent: Intent): Boolean = start(intent)

    /** True while the lock screen shows; Wakey never tries to get past it. */
    val isLocked: Boolean get() = screen()?.isLocked == true || keyguard?.isKeyguardLocked == true

    /** Launcher labels of installed apps, for STT keyterms and the model prompt. */
    fun installedAppLabels(): List<String> =
        launcherApps().map { it.label }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }

    /** Like [openApp], but reads the package list off the caller's thread and reports the target package. */
    internal suspend fun startApp(name: String): AppLaunch = launch(name, withContext(Dispatchers.IO) { resolve(name) })

    /** True if [name] matches an installed app (or a known app type), without launching anything. */
    suspend fun canOpen(name: String): Boolean = withContext(Dispatchers.IO) { resolve(name) != null }

    private suspend fun openAndVerify(name: String): ActionOutcome {
        val launch = startApp(name)
        val packageName = launch.packageName ?: return launch.outcome
        val controller = screen() ?: return launch.outcome
        return if (controller.awaitForeground(packageName)) ActionOutcome(true, "Opened ${launch.label ?: name}.") else launch.outcome
    }

    private fun launch(name: String, target: LaunchTarget?): AppLaunch {
        if (isLocked) return AppLaunch(ActionOutcome(false, LOCKED_MESSAGE))
        if (target == null) return AppLaunch(ActionOutcome(false, "I couldn't find an app called ${name.trim()} on this phone."))
        // Without screen control Android silently blocks launches while Wakey is in the background.
        if (screen() == null && !appVisible()) return AppLaunch(ActionOutcome(false, BACKGROUND_MESSAGE))
        if (!start(target.intent)) return AppLaunch(ActionOutcome(false, "I couldn't open ${target.label}."))
        return AppLaunch(ActionOutcome(true, "Opening ${target.label}."), target.packageName, target.label)
    }

    private fun resolve(name: String): LaunchTarget? {
        AppMatcher.match(name, launcherApps())?.let { app ->
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.packageName, app.activityName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return LaunchTarget(intent, app.label, app.packageName)
        }
        // A well-known app type with no launcher match (e.g. an OEM calculator under another name).
        val alias = AppAliases.forName(name) ?: return null
        val intent = alias.fallbackCategory?.let { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, it) }
            ?: alias.fallbackAction?.let { Intent(it) }
            ?: return null
        return LaunchTarget(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), alias.label, null)
    }

    private fun start(intent: Intent): Boolean {
        if (screen()?.launch(intent) == true) return true
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun navigate(home: Boolean): ActionOutcome {
        val controller = screen() ?: return ActionOutcome(
            false,
            "Turn on Wakey screen control in Settings › Accessibility › Wakey so I can ${if (home) "go home" else "go back"}.",
        )
        if (isLocked) return ActionOutcome(false, LOCKED_MESSAGE)
        val result = if (home) controller.home() else controller.back()
        return if (result.success) ActionOutcome(true, if (home) "Going home." else "Going back.") else result
    }

    private fun launcherApps(): List<LauncherApp> {
        val now = SystemClock.elapsedRealtime()
        appCache?.takeIf { now - it.loadedAtMs < APP_CACHE_MS }?.let { return it.apps }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val apps = infos.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            LauncherApp(info.loadLabel(pm).toString().trim(), activity.packageName, activity.name)
        }
        appCache = CachedApps(now, apps)
        return apps
    }

    private class LaunchTarget(val intent: Intent, val label: String, val packageName: String?)
    private class CachedApps(val loadedAtMs: Long, val apps: List<LauncherApp>)

    internal companion object {
        const val LOCKED_MESSAGE = "Please unlock your phone first — I can't bypass the lock screen."
        const val BACKGROUND_MESSAGE =
            "Android won't let me open apps from the background. Turn on Wakey screen control in Settings › Accessibility, or open Wakey first."
        private val NO_FLASHLIGHT = ActionOutcome(false, "This phone has no flashlight.")
        private const val APP_CACHE_MS = 30_000L
    }
}

/** An app launch request; [packageName] is what should reach the foreground if it worked. */
internal data class AppLaunch(val outcome: ActionOutcome, val packageName: String? = null, val label: String? = null)

/**
 * Waits a bounded time for [packageName] to reach the foreground; cold starts can take a few seconds.
 * Polls instead of waiting for the UI to settle, since launch animations keep content changing.
 */
internal suspend fun ScreenController.awaitForeground(packageName: String, timeoutMs: Long = FOREGROUND_TIMEOUT_MS): Boolean {
    repeat((timeoutMs / FOREGROUND_POLL_MS).toInt()) {
        if (foregroundPackage == packageName) return true
        delay(FOREGROUND_POLL_MS)
    }
    return foregroundPackage == packageName
}

private const val FOREGROUND_POLL_MS = 150L
private const val FOREGROUND_TIMEOUT_MS = 5_000L
