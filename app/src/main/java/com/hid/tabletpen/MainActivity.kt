package com.hid.tabletpen

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow

class MainActivity : AppCompatActivity(),
    BluetoothHidManager.Listener {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_DISCOVERABLE = 2
    }

    private lateinit var hidManager: BluetoothHidManager
    private lateinit var settings: AppSettings
    private lateinit var btScreenshot: BluetoothScreenshot

    private lateinit var statusText: TextView
    private lateinit var connectionText: TextView
    private lateinit var drawPad: DrawPadView
    private lateinit var discoverableBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var screenshotBtn: Button
    private lateinit var focusBtn: Button

    private var hidRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings.load(this)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.status_text)
        connectionText = findViewById(R.id.connection_text)
        drawPad = findViewById(R.id.draw_pad)
        discoverableBtn = findViewById(R.id.btn_discoverable)
        clearBtn = findViewById(R.id.btn_clear)
        settingsBtn = findViewById(R.id.btn_settings)

        screenshotBtn = findViewById(R.id.btn_screenshot)
        focusBtn = findViewById(R.id.btn_focus)

        hidManager = BluetoothHidManager(this)
        hidManager.listener = this
        hidManager.setAutoConnectDevice(AppSettings.loadLastDevice(this))

        btScreenshot = BluetoothScreenshot()
        btScreenshot.listener = object : BluetoothScreenshot.Listener {
            override fun onScreenshotReceived(bitmap: android.graphics.Bitmap) {
                drawPad.setScreenshot(bitmap)
                if (settings.clearOnScreenshot) drawPad.clearStrokes()
                screenshotBtn.text = "Screenshot"
                screenshotBtn.isEnabled = true
            }
            override fun onScreenshotError(message: String) {
                statusText.text = "Screenshot: $message"
                screenshotBtn.text = "Screenshot"
                screenshotBtn.isEnabled = true
            }
        }

        applySettingsToDrawPad()

        drawPad.onPenEvent = { event -> handlePenEvent(event) }
        drawPad.onMouseEvent = { event -> handleMouseEvent(event) }

        discoverableBtn.setOnClickListener { requestDiscoverable() }
        clearBtn.setOnClickListener { drawPad.clearStrokes() }
        screenshotBtn.setOnClickListener { onScreenshotClicked() }
        focusBtn.setOnClickListener { onFocusClicked() }
        settingsBtn.setOnClickListener { showSettingsDialog() }

        updateUI()
        applyOrientation()

        if (checkPermissions()) {
            hidManager.start()
        } else {
            requestPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hidManager.stop()
    }

    private fun applySettingsToDrawPad() {
        drawPad.targetAspectRatio = settings.targetAspectRatio.ratio
        drawPad.inputMode = settings.inputMode
    }

    // ---- Pen event → BT HID digitizer report ----

    private fun handlePenEvent(event: DrawPadView.PenEvent) {
        if (!hidManager.isConnected) return

        // Map normalized coords through focus rect if active
        val fr = drawPad.focusRect
        val mappedX: Float
        val mappedY: Float
        if (fr != null) {
            // Pen 0-1 maps to just the focus area of the screen
            mappedX = fr.left + event.normalizedX * fr.width()
            mappedY = fr.top + event.normalizedY * fr.height()
        } else {
            mappedX = event.normalizedX
            mappedY = event.normalizedY
        }

        val x = (mappedX * HidDescriptor.X_MAX).toInt()
        val y = (mappedY * HidDescriptor.Y_MAX).toInt()

        // Floor + curve: floor guarantees minimum pressure when tip is down,
        // remaining range (1-floor) is scaled by the pressure curve
        val floor = settings.pressureFloor
        val curved = event.pressure.toDouble().pow(settings.pressureExponent.toDouble()).toFloat()
        val pressure = if (event.tipDown) {
            ((floor + curved * (1f - floor)) * HidDescriptor.PRESSURE_MAX).toInt()
        } else {
            0
        }

        val sent = hidManager.sendDigitizerReport(
            tipDown = event.tipDown,
            barrel = event.barrel,
            inRange = event.inRange,
            x = x, y = y, pressure = pressure
        )
        if (event.tipDown) {
            android.util.Log.d("HidReport", "tip=${ event.tipDown} x=$x y=$y p=$pressure sent=$sent")
        }
    }

    // ---- Mouse event → BT HID mouse report ----

    private fun handleMouseEvent(event: DrawPadView.MouseEvent) {
        if (!hidManager.isConnected) return

        if (event.scroll != 0f) {
            // Scroll event
            val scroll = event.scroll.toInt().coerceIn(-127, 127)
            hidManager.sendMouseReport(false, false, false, 0, 0, scroll)
            return
        }

        val scaledDx = (event.dx * settings.mouseSensitivity).toInt()
        val scaledDy = (event.dy * settings.mouseSensitivity).toInt()

        sendMouseDelta(event.leftButton, event.rightButton, scaledDx, scaledDy)
    }

    private fun sendMouseDelta(left: Boolean, right: Boolean, dx: Int, dy: Int) {
        var rx = dx
        var ry = dy
        do {
            val sx = rx.coerceIn(-127, 127)
            val sy = ry.coerceIn(-127, 127)
            hidManager.sendMouseReport(left, right, false, sx, sy)
            rx -= sx
            ry -= sy
        } while (rx != 0 || ry != 0)
    }

    // ---- Orientation ----

    private fun applyOrientation() {
        requestedOrientation = when (settings.orientationMode) {
            OrientationMode.AUTO -> {
                if (settings.targetAspectRatio.ratio >= 1.0f)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            OrientationMode.PORTRAIT -> when (settings.rotationDegrees) {
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            OrientationMode.LANDSCAPE -> when (settings.rotationDegrees) {
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }

    // ---- Settings dialog ----

    @SuppressLint("SetTextI18n")
    private fun showSettingsDialog() {
        val padding = 48

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 32, padding, 32)
        }

        // Mode
        layout.addView(TextView(this).apply { text = "Input Mode"; setPadding(0, 16, 0, 4) })
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Digitizer (Pen)", "Mouse"))
            setSelection(settings.inputMode.ordinal)
        }
        layout.addView(modeSpinner)

        // Orientation
        layout.addView(TextView(this).apply { text = "Orientation"; setPadding(0, 16, 0, 4) })
        val orientSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto", "Portrait", "Landscape"))
            setSelection(settings.orientationMode.ordinal)
        }
        layout.addView(orientSpinner)

        // Rotation
        layout.addView(TextView(this).apply { text = "Rotation"; setPadding(0, 16, 0, 4) })
        val rotationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("0\u00B0", "90\u00B0", "180\u00B0", "270\u00B0"))
            setSelection(settings.rotationDegrees / 90)
        }
        layout.addView(rotationSpinner)

        // Aspect ratio
        layout.addView(TextView(this).apply { text = "Target Aspect Ratio"; setPadding(0, 16, 0, 4) })
        val ratioSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("16:10 (MacBook)", "16:9", "3:2"))
            val idx = AspectRatio.PRESETS.indexOfFirst {
                it.w == settings.targetAspectRatio.w && it.h == settings.targetAspectRatio.h
            }
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(ratioSpinner)

        // Pressure floor
        // Clear on screenshot
        val clearCheck = android.widget.CheckBox(this).apply {
            text = "Clear strokes on new screenshot"
            isChecked = settings.clearOnScreenshot
        }
        layout.addView(clearCheck)

        layout.addView(TextView(this).apply { text = "Pressure Min Floor"; setPadding(0, 16, 0, 4) })
        val floorLabel = TextView(this).apply {
            text = "Floor: ${"%.0f".format(settings.pressureFloor * 100)}% (higher = lighter touch draws)"
        }
        val floorSeek = SeekBar(this).apply {
            max = 100
            progress = (settings.pressureFloor * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    floorLabel.text = "Floor: ${p}% (higher = lighter touch draws)"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(floorSeek)
        layout.addView(floorLabel)

        // Pressure curve
        layout.addView(TextView(this).apply { text = "Pressure Curve"; setPadding(0, 16, 0, 4) })
        val pressureLabel = TextView(this).apply {
            text = "Exponent: ${"%.2f".format(settings.pressureExponent)} (lower = more sensitive)"
        }
        val pressureSeek = SeekBar(this).apply {
            max = 100
            progress = ((settings.pressureExponent - 0.1f) / 1.9f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val exp = 0.1f + p / 100f * 1.9f
                    pressureLabel.text = "Exponent: ${"%.2f".format(exp)} (lower = more sensitive)"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(pressureSeek)
        layout.addView(pressureLabel)

        // Mouse sensitivity
        layout.addView(TextView(this).apply { text = "Mouse Sensitivity"; setPadding(0, 16, 0, 4) })
        val mouseLabel = TextView(this).apply { text = "Speed: ${"%.1f".format(settings.mouseSensitivity)}x" }
        val mouseSeek = SeekBar(this).apply {
            max = 100
            progress = ((settings.mouseSensitivity - 0.5f) / 4.5f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val sens = 0.5f + p / 100f * 4.5f
                    mouseLabel.text = "Speed: ${"%.1f".format(sens)}x"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(mouseSeek)
        layout.addView(mouseLabel)

        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                settings = settings.copy(
                    inputMode = InputMode.entries[modeSpinner.selectedItemPosition],
                    orientationMode = OrientationMode.entries[orientSpinner.selectedItemPosition],
                    rotationDegrees = rotationSpinner.selectedItemPosition * 90,
                    targetAspectRatio = AspectRatio.PRESETS.getOrElse(ratioSpinner.selectedItemPosition) { AspectRatio.RATIO_16_10 },
                    clearOnScreenshot = clearCheck.isChecked,
                    pressureFloor = floorSeek.progress / 100f,
                    pressureExponent = 0.1f + pressureSeek.progress / 100f * 1.9f,
                    mouseSensitivity = 0.5f + mouseSeek.progress / 100f * 4.5f
                )
                AppSettings.save(this, settings)
                applySettingsToDrawPad()
                applyOrientation()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Focus ----

    @SuppressLint("SetTextI18n")
    private fun onFocusClicked() {
        if (drawPad.focusRect != null) {
            // Already focused — reset
            drawPad.resetFocus()
            focusBtn.text = "Focus"
            statusText.text = "Focus cleared"
        } else if (drawPad.focusSelecting) {
            // Cancel selection
            drawPad.focusSelecting = false
            focusBtn.text = "Focus"
        } else {
            // Start selection
            drawPad.focusSelecting = true
            focusBtn.text = "Reset Focus"
            statusText.text = "Draw a rectangle to select focus area"
        }
    }

    // ---- Screenshot ----

    private fun onScreenshotClicked() {
        screenshotBtn.text = "Capturing..."
        screenshotBtn.isEnabled = false
        btScreenshot.requestScreenshot()
    }

    // ---- Bluetooth HID listener ----

    override fun onHidReady(registered: Boolean) {
        hidRegistered = registered
        runOnUiThread { updateUI() }
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceConnected(device: BluetoothDevice) {
        AppSettings.saveLastDevice(this, device.address)
        hidManager.setAutoConnectDevice(device.address)
        runOnUiThread { updateUI() }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread { updateUI() }
    }

    override fun onError(message: String) {
        runOnUiThread { statusText.text = "Error: $message" }
    }

    // ---- UI ----

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun updateUI() {
        val modeName = if (settings.inputMode == InputMode.DIGITIZER) "Pen" else "Mouse"
        statusText.text = when {
            !hidRegistered -> "Registering HID device..."
            hidManager.isConnected -> "Connected [$modeName] — draw to send input"
            else -> "Waiting for connection. Make tablet discoverable, then pair from Mac."
        }

        connectionText.text = if (hidManager.isConnected) {
            "Connected to: ${hidManager.connectedDeviceName ?: "Unknown"}"
        } else {
            "Not connected"
        }

        connectionText.setTextColor(
            if (hidManager.isConnected) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
        )

        discoverableBtn.visibility = if (!hidManager.isConnected) View.VISIBLE else View.GONE
    }

    // ---- Discoverable ----

    @SuppressLint("MissingPermission")
    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        startActivityForResult(intent, REQUEST_DISCOVERABLE)
    }

    // ---- Permissions ----

    private fun checkPermissions(): Boolean {
        val needed = requiredPermissions()
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS)
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return perms.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                hidManager.start()
            } else {
                statusText.text = "Bluetooth permissions required"
            }
        }
    }
}
