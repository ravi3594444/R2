package ai.wakey.android.ui

import ai.wakey.android.accessibility.AccessibilityStatus
import ai.wakey.android.service.BatteryOptimization
import android.Manifest
import android.app.role.RoleManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** What Wakey has been allowed to do. Re-read on every resume, since all of it changes in system Settings. */
data class SetupStatus(
    val microphone: Boolean,
    val notifications: Boolean,
    val screenControl: Boolean,
    val batteryUnrestricted: Boolean,
    /** Wakey is the default digital assistant: long-press power/home and background restarts work. */
    val defaultAssistant: Boolean,
) {
    /** The main screen's "Setup needed" card covers only what blocks voice or phone control. */
    val needsAttention: Boolean get() = !microphone || !screenControl

    companion object {
        fun read(context: Context): SetupStatus = SetupStatus(
            microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: true,
            screenControl = AccessibilityStatus.isEnabled(context),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            defaultAssistant = context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
        )
    }
}

/** Why a permission request needs a follow-up explanation. */
enum class PermissionNotice { MicrophoneDenied, NotificationsDenied }

/** True when notifications need a runtime grant (Android 13+); earlier versions only have the Settings switch. */
val notificationsNeedRuntimeGrant: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Setup status plus the permission requests and system-settings shortcuts that change it.
 * Created once at the root with [rememberWakeySetup] and shared by every screen.
 */
@Stable
class WakeySetup internal constructor(private val context: Context) {
    var status by mutableStateOf(SetupStatus.read(context))
        private set

    /** A denial to explain, with a way into app settings. */
    var notice by mutableStateOf<PermissionNotice?>(null)
        private set

    internal var launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>? = null
    private var pendingAction: (() -> Unit)? = null
    private var requested: Set<String> = emptySet()
    private var notificationsNoticeShown = false

    fun refresh() {
        status = SetupStatus.read(context)
    }

    /**
     * Runs [action] once the microphone is allowed, asking first if needed. Notifications are asked
     * for alongside, and always before wake listening, because the listening notification carries
     * Stop and approval prompts; declining them does not block [action].
     */
    fun withMicrophone(forWakeWord: Boolean = false, action: () -> Unit) {
        refresh()
        val missing = buildList {
            if (!status.microphone) add(Manifest.permission.RECORD_AUDIO)
            if (notificationsPermissionMissing() && (forWakeWord || !status.microphone)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            request(missing)
        }
    }

    /** Microphone and notifications together, for onboarding and the setup checklist. */
    fun requestVoicePermissions() {
        pendingAction = null
        refresh()
        request(
            buildList {
                if (!status.microphone) add(Manifest.permission.RECORD_AUDIO)
                if (notificationsPermissionMissing()) add(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }

    fun requestNotifications() {
        pendingAction = null
        if (notificationsPermissionMissing()) {
            request(listOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            // Granted (or pre-13) but switched off by the user: only Settings can turn them back on.
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }

    private fun notificationsPermissionMissing(): Boolean = notificationsNeedRuntimeGrant &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun request(permissions: List<String>) {
        if (permissions.isEmpty()) return
        requested = permissions.toSet()
        launcher?.launch(permissions.toTypedArray())
    }

    /** Re-reads the grants instead of using the result map, which comes back empty if the request was interrupted. */
    internal fun onPermissionResult() {
        refresh()
        val action = pendingAction
        pendingAction = null
        val micDenied = Manifest.permission.RECORD_AUDIO in requested && !status.microphone
        val notificationsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            Manifest.permission.POST_NOTIFICATIONS in requested && !status.notifications
        if (!micDenied) action?.invoke()
        notice = when {
            micDenied -> PermissionNotice.MicrophoneDenied
            // Explain once per launch when it happened on the way to something else; always when asked directly.
            notificationsDenied && (action == null || !notificationsNoticeShown) -> {
                notificationsNoticeShown = true
                PermissionNotice.NotificationsDenied
            }
            else -> null
        }
    }

    fun dismissNotice() {
        notice = null
    }

    fun openAppSettings() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    fun openAccessibilitySettings() {
        open(AccessibilityStatus.settingsIntent())
    }

    /** The direct "allow?" dialog, else the system exemption list, else App info. */
    fun openBatterySettings() {
        if (!BatteryOptimization.openSettings(context)) openAppSettings()
    }

    /** "Digital assistant app" lives under Default apps; the assistant role can't be requested directly. */
    fun openAssistantSettings() {
        if (!open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    private fun open(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
fun rememberWakeySetup(): WakeySetup {
    val context = LocalContext.current
    val setup = remember(context) { WakeySetup(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        setup.onPermissionResult()
    }
    SideEffect { setup.launcher = launcher }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh() }
    return setup
}
