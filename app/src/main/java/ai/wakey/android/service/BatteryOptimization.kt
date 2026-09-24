package ai.wakey.android.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation status and a shortcut to change it. An exemption keeps wake listening
 * reliable with the screen off on phones that restrict background work aggressively.
 *
 * Asks with the direct "allow?" dialog first (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); phones
 * without it get the settings list or the app-info page.
 */
object BatteryOptimization {
    /** True when the user has exempted Wakey from battery optimisation. */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Opens the system battery-optimisation list, or Wakey's app-info page on devices without that
     * screen. Returns false if neither could be opened.
     */
    fun openSettings(context: Context): Boolean = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    ).any { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
