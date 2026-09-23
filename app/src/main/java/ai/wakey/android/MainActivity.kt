package ai.wakey.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A text-first vertical slice; Claude's voice and LLM work will call the same actions. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private var pendingTorch: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(Color.rgb(19, 20, 22))
        }
        val title = TextView(this).apply {
            text = "Wakey"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val description = TextView(this).apply {
            text = "Starter: try ‘open Settings’, ‘open YouTube’, or ‘flashlight on’. Voice comes next."
            setTextColor(Color.LTGRAY)
            textSize = 16f
        }
        val input = EditText(this).apply {
            hint = "Type a phone command"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        status = TextView(this).apply {
            text = "Ready. Enable Wakey screen control in Android Accessibility settings to inspect other apps."
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val runButton = Button(this).apply {
            text = "Run command"
            setOnClickListener { runCommand(input.text.toString()) }
        }
        val inspectButton = Button(this).apply {
            text = "Inspect last external screen"
            setOnClickListener { status.text = WakeyAccessibilityService.lastExternalScreen }
        }
        column.addView(title)
        column.addView(description)
        column.addView(input)
        column.addView(runButton)
        column.addView(inspectButton)
        column.addView(status)
        val scroll = ScrollView(this).apply { addView(column, ViewGroup.LayoutParams(-1, -1)) }
        setContentView(scroll)
    }

    private fun runCommand(raw: String) {
        val command = raw.trim()
        when {
            command.equals("flashlight on", true) || command.equals("turn on flashlight", true) -> setTorch(true)
            command.equals("flashlight off", true) || command.equals("turn off flashlight", true) -> setTorch(false)
            command.startsWith("open ", true) -> openApp(command.substring(5).trim())
            command.startsWith("launch ", true) -> openApp(command.substring(7).trim())
            else -> status.text = "Unknown direct command. The agent navigation loop is the next milestone."
        }
    }

    private fun openApp(name: String) {
        if (name.isBlank()) {
            status.text = "Say which app to open."
            return
        }
        if (name.equals("settings", true)) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            status.text = "Requested Android Settings."
            return
        }
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(launcherQuery, 0)
        val match = apps.firstOrNull { it.loadLabel(packageManager).toString().equals(name, true) }
        if (match == null) {
            status.text = "No installed app named ‘$name’ was found."
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(match.activityInfo.packageName, match.activityInfo.name))
            status.text = "Requested ${match.loadLabel(packageManager)}."
        } catch (error: Exception) {
            status.text = "Could not launch $name: ${error.javaClass.simpleName}"
        }
    }

    private fun setTorch(on: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingTorch = on
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }
        try {
            val cameras = getSystemService(CAMERA_SERVICE) as CameraManager
            val torchCamera = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (torchCamera == null) {
                status.text = "This device has no available flashlight."
                return
            }
            cameras.setTorchMode(torchCamera, on)
            status.text = if (on) "Flashlight on." else "Flashlight off."
        } catch (error: Exception) {
            status.text = "Could not change flashlight: ${error.javaClass.simpleName}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        val requested = pendingTorch
        pendingTorch = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && requested != null) {
            setTorch(requested)
        } else {
            status.text = "Camera permission is needed to control the flashlight."
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 2
    }
}
