package ai.wakey.android.accessibility

import ai.wakey.android.WakeyAccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

object AccessibilityStatus {
    /** True if the user has enabled Wakey screen control in Android settings. */
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WakeyAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** True when the service is enabled and currently bound (it can act right now). */
    fun isConnected(): Boolean = WakeyAccessibilityService.instance != null

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
