package com.hid.tabletpen

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(),
    BluetoothHidManager.Listener {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_DISCOVERABLE = 2
    }

    private lateinit var hidManager: BluetoothHidManager
    private lateinit var settings: AppSettings
    private lateinit var btScreenshot: BluetoothScreenshot

    private lateinit var deviceSpinner: Spinner
    private lateinit var drawPad: DrawPadView
    private lateinit var discoverableBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var screenshotBtn: Button
    private lateinit var streamBtn: Button
    private lateinit var focusBtn: Button
    private lateinit var shortcutContainer: android.widget.LinearLayout

    private var hidRegistered = false
    private var detectedApp: String? = null

    // Block pairing requests from non-target devices
    private val pairingReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val target = hidManager.connectedDeviceAddress ?: AppSettings.loadLastDevice(context)
            if (target != null && !device.address.equals(target, ignoreCase = true)) {
                android.util.Log.i("PairingBlock", "Rejecting pairing from non-target: ${device.name} (${device.address})")
                try {
                    device.setPairingConfirmation(false)
                } catch (_: Exception) {}
                try {
                    // Cancel the bond process to dismiss the dialog
                    val method = device.javaClass.getMethod("cancelBondProcess")
                    method.invoke(device)
                } catch (_: Exception) {}
                abortBroadcast()
            }
        }
    }

    // Auto-recapture: screenshot after pen lifts
    private var autoRecaptureRunnable: Runnable? = null
    private val AUTO_RECAPTURE_DELAY = 1000L

    // Device selection — populated from live bondedDevices filtered to computers
    private var deviceAddresses = listOf<String>()
    private var deviceNames = linkedMapOf<String, String>() // address → name (for display)
    private var suppressSpinnerEvent = false

    // Animated dots for loading states
    private val uiHandler = Handler(Looper.getMainLooper())
    private var dotCount = 0
    private val dotAnimRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            updateUI()
            uiHandler.postDelayed(this, 400)
        }
    }
    private var dotAnimRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings.load(this)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        deviceSpinner = findViewById(R.id.device_spinner)
        drawPad = findViewById(R.id.draw_pad)
        discoverableBtn = findViewById(R.id.btn_discoverable)
        clearBtn = findViewById(R.id.btn_clear)
        settingsBtn = findViewById(R.id.btn_settings)
        screenshotBtn = findViewById(R.id.btn_screenshot)
        streamBtn = findViewById(R.id.btn_stream)
        focusBtn = findViewById(R.id.btn_focus)
        shortcutContainer = findViewById(R.id.shortcut_container)

        hidManager = BluetoothHidManager(this)
        hidManager.listener = this

        // Device selector — populated from bonded computer devices
        refreshDeviceSpinner()
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvent) { suppressSpinnerEvent = false; return }
                if (pos >= deviceAddresses.size) return
                val address = deviceAddresses[pos]
                if (address == hidManager.connectedDeviceAddress) return
                connectToDevice(address)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Register pairing blocker with max priority to intercept before system UI
        val pairingFilter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        registerReceiver(pairingReceiver, pairingFilter)

        btScreenshot = BluetoothScreenshot(this)
        btScreenshot.listener = object : BluetoothScreenshot.Listener {
            override fun onScreenshotReceived(bitmap: android.graphics.Bitmap) {
                drawPad.setScreenshot(bitmap)
                btScreenshot.focusRect = drawPad.focusRect
                if (settings.clearOnScreenshot) drawPad.clearStrokes()
                screenshotBtn.text = "Screenshot"
                screenshotBtn.isEnabled = true
            }
            override fun onScreenshotError(message: String) {
                screenshotBtn.text = "Screenshot"
                screenshotBtn.isEnabled = true
            }
            @SuppressLint("SetTextI18n")
            override fun onWifiStateChanged(connected: Boolean) {
                // Show Stream button when WiFi OR BT is connected (BT H.264 streaming)
                streamBtn.visibility = if (connected || btScreenshot.isBtConnected) View.VISIBLE else View.GONE
                if (!connected && !btScreenshot.isBtConnected && btScreenshot.isStreaming) {
                    streamBtn.text = "Stream"
                }
            }
            override fun onStreamFrame(bitmap: android.graphics.Bitmap) {
                drawPad.setScreenshot(bitmap)
            }
            override fun onDeltaFrame(tiles: List<BluetoothScreenshot.DeltaTile>) {
                drawPad.composeDelta(tiles)
            }
            @SuppressLint("SetTextI18n")
            override fun onAppDetected(appName: String) {
                handleAppDetected(appName)
            }
        }
        btScreenshot.startServer()
        btScreenshot.screenshotQuality = settings.screenshotQuality
        btScreenshot.streamMethod = settings.streamMethod
        btScreenshot.streamQuality = settings.streamQuality

        applySettingsToDrawPad()

        drawPad.onPenEvent = { event -> handlePenEvent(event) }
        drawPad.onMouseEvent = { event -> handleMouseEvent(event) }
        drawPad.onPinchZoom = { scaleFactor -> handlePinchZoom(scaleFactor) }
        drawPad.onShortcut = { shortcut -> sendShortcut(shortcut.modifiers, *shortcut.keycodes.toIntArray()) }

        discoverableBtn.setOnClickListener { requestDiscoverable() }
        clearBtn.setOnClickListener { drawPad.clearStrokes() }
        screenshotBtn.setOnClickListener { onScreenshotClicked() }
        streamBtn.setOnClickListener { onStreamClicked() }
        focusBtn.setOnClickListener { onFocusClicked() }
        settingsBtn.setOnClickListener { showSettingsDialog() }
        setupShortcutButtons()

        updateUI()
        applyOrientation()

        // Check for app updates
        UpdateChecker(this).checkInBackground { info ->
            UpdateChecker(this).showUpdateDialog(info)
        }

        if (checkPermissions()) {
            hidManager.start()
            // Auto-connect last device if it's still bonded
            val lastAddr = AppSettings.loadLastDevice(this)
            if (lastAddr != null && lastAddr in deviceNames) {
                hidManager.setAutoConnectDevice(lastAddr)
                btScreenshot.setTargetDevice(lastAddr)
            }
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceSpinner()
        if (checkPermissions()) {
            hidManager.ensureConnected()
        }
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pairingReceiver) } catch (_: Exception) {}
        stopDotAnimation()
        btScreenshot.stopServer()
        hidManager.stop()
    }

    private fun applySettingsToDrawPad() {
        drawPad.targetAspectRatio = settings.targetAspectRatio.ratio
        drawPad.inputMode = settings.inputMode
        drawPad.scrollSensitivity = settings.scrollSensitivity
        drawPad.pinchThreshold = settings.pinchThreshold
        drawPad.cursorStyle = settings.cursorStyle
        drawPad.strokeColorSetting = settings.strokeColor
        drawPad.showGhostStroke = settings.showGhostStroke
        drawPad.shortcuts = settings.shortcuts
    }

    // ---- Pen event → BT HID digitizer report ----

    private fun handlePenEvent(event: DrawPadView.PenEvent) {
        if (!hidManager.isConnected) return

        val (mappedX, mappedY) = PenMath.mapThroughFocus(
            event.normalizedX, event.normalizedY, drawPad.focusRect
        )
        val x = (mappedX * HidDescriptor.X_MAX).toInt()
        val y = (mappedY * HidDescriptor.Y_MAX).toInt()
        val pressure = PenMath.calculatePressure(
            event.tipDown, event.pressure,
            settings.pressureFloor, settings.pressureExponent,
            HidDescriptor.PRESSURE_MAX
        )

        val isEraser = event.toolType == android.view.MotionEvent.TOOL_TYPE_ERASER
        val tiltX = (event.tiltX * HidDescriptor.TILT_MAX).toInt()
        val tiltY = (event.tiltY * HidDescriptor.TILT_MAX).toInt()
        hidManager.sendDigitizerReport(
            tipDown = event.tipDown,
            barrel = event.barrel,
            inRange = event.inRange,
            x = x, y = y, pressure = pressure,
            eraser = isEraser,
            tiltX = tiltX, tiltY = tiltY
        )

        // Auto-recapture: schedule screenshot after pen lifts
        if (settings.autoRecapture) {
            if (!event.tipDown && !event.inRange) {
                scheduleAutoRecapture()
            } else if (event.tipDown) {
                cancelAutoRecapture()
            }
        }
    }

    private fun scheduleAutoRecapture() {
        cancelAutoRecapture()
        if (!btScreenshot.isMacConnected) return
        autoRecaptureRunnable = Runnable {
            btScreenshot.requestScreenshot()
        }
        uiHandler.postDelayed(autoRecaptureRunnable!!, AUTO_RECAPTURE_DELAY)
    }

    private fun cancelAutoRecapture() {
        autoRecaptureRunnable?.let { uiHandler.removeCallbacks(it) }
        autoRecaptureRunnable = null
    }

    // ---- Keyboard shortcuts ----

    @android.annotation.SuppressLint("SetTextI18n")
    private fun setupShortcutButtons() {
        shortcutContainer.removeAllViews()
        for (shortcut in settings.shortcuts.take(4)) {
            val btn = Button(this).apply {
                text = shortcut.name
                textSize = 11f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, (36 * resources.displayMetrics.density).toInt()
                ).apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
                setOnClickListener { sendShortcut(shortcut.modifiers, *shortcut.keycodes.toIntArray()) }
            }
            shortcutContainer.addView(btn)
        }
    }

    private fun sendShortcut(modifiers: Int, vararg keycodes: Int) {
        if (!hidManager.isConnected) return
        hidManager.sendKeyboardReport(modifiers, *keycodes)
        uiHandler.postDelayed({
            hidManager.sendKeyboardReport(0)  // release all keys
        }, 50)
    }

    // ---- Mouse event → BT HID mouse report ----

    private fun handleMouseEvent(event: DrawPadView.MouseEvent) {
        if (!hidManager.isConnected) return

        if (event.horizontalScroll != 0f) {
            // Horizontal scroll: Shift + scroll wheel (macOS convention)
            hidManager.sendKeyboardReport(HidDescriptor.MOD_LEFT_SHIFT)
            val scroll = event.horizontalScroll.toInt().coerceIn(-127, 127)
            hidManager.sendMouseReport(false, false, false, 0, 0, scroll)
            hidManager.sendKeyboardReport(0)
            return
        }

        if (event.scroll != 0f) {
            val scroll = event.scroll.toInt().coerceIn(-127, 127)
            hidManager.sendMouseReport(false, false, false, 0, 0, scroll)
            return
        }

        val scaledDx = (event.dx * settings.mouseSensitivity).toInt()
        val scaledDy = (event.dy * settings.mouseSensitivity).toInt()

        sendMouseDelta(event.leftButton, event.rightButton, scaledDx, scaledDy)
    }

    private fun sendMouseDelta(left: Boolean, right: Boolean, dx: Int, dy: Int) {
        for ((sx, sy) in PenMath.chunkMouseDeltas(dx, dy)) {
            hidManager.sendMouseReport(left, right, false, sx, sy)
        }
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

        // Cursor style
        layout.addView(TextView(this).apply { text = "Cursor Style"; setPadding(0, 16, 0, 4) })
        val cursorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                CursorStyle.LABELS)
            setSelection(settings.cursorStyle.ordinal)
        }
        layout.addView(cursorSpinner)

        // Stroke color
        layout.addView(TextView(this).apply { text = "Stroke Color"; setPadding(0, 16, 0, 4) })
        val strokeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                StrokeColor.LABELS)
            setSelection(settings.strokeColor.ordinal)
        }
        layout.addView(strokeSpinner)

        // Auto-recapture
        val recaptureCheck = android.widget.CheckBox(this).apply {
            text = "Auto-recapture after drawing"
            isChecked = settings.autoRecapture
        }
        layout.addView(recaptureCheck)

        // Ghost stroke prediction
        val ghostCheck = android.widget.CheckBox(this).apply {
            text = "Show ghost stroke prediction"
            isChecked = settings.showGhostStroke
        }
        layout.addView(ghostCheck)

        // Screenshot quality
        layout.addView(TextView(this).apply { text = "Screenshot Quality"; setPadding(0, 16, 0, 4) })
        val ssQualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                CaptureQuality.LABELS)
            setSelection(settings.screenshotQuality.ordinal)
        }
        layout.addView(ssQualitySpinner)

        // Stream method
        layout.addView(TextView(this).apply { text = "Stream Method"; setPadding(0, 16, 0, 4) })
        val streamMethodDesc = TextView(this).apply {
            text = settings.streamMethod.description
            setTextColor(android.graphics.Color.GRAY)
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.ITALIC)
            setPadding(0, 4, 0, 8)
        }
        val streamMethodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                StreamMethod.LABELS)
            setSelection(settings.streamMethod.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    streamMethodDesc.text = StreamMethod.entries[pos].description
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        layout.addView(streamMethodSpinner)
        layout.addView(streamMethodDesc)

        // Stream quality
        layout.addView(TextView(this).apply { text = "Stream Quality"; setPadding(0, 16, 0, 4) })
        val streamQualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                CaptureQuality.LABELS)
            setSelection(settings.streamQuality.ordinal)
        }
        layout.addView(streamQualitySpinner)

        // Auto-switch preset by Mac app
        val autoSwitchCheck = android.widget.CheckBox(this).apply {
            text = "Auto-switch shortcuts by Mac app"
            isChecked = settings.autoSwitchPreset
        }
        layout.addView(autoSwitchCheck)

        // Shortcut preset
        layout.addView(TextView(this).apply { text = "Shortcut Preset"; setPadding(0, 16, 0, 4) })
        val presetNames = SHORTCUT_PRESETS.keys.toList()
        val presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetNames)
            setSelection(0)
        }
        layout.addView(presetSpinner)

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

        // Scroll sensitivity
        layout.addView(TextView(this).apply { text = "Scroll Sensitivity"; setPadding(0, 16, 0, 4) })
        val scrollSensLabel = TextView(this).apply { text = "Speed: ${"%.1f".format(settings.scrollSensitivity)}x" }
        val scrollSensSeek = SeekBar(this).apply {
            max = 100
            progress = ((settings.scrollSensitivity - 0.5f) / 4.5f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    scrollSensLabel.text = "Speed: ${"%.1f".format(0.5f + p / 100f * 4.5f)}x"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(scrollSensSeek)
        layout.addView(scrollSensLabel)

        // Pinch sensitivity
        layout.addView(TextView(this).apply { text = "Pinch Zoom Sensitivity"; setPadding(0, 16, 0, 4) })
        val pinchSensLabel = TextView(this).apply { text = "Multiplier: ${"%.0f".format(settings.pinchSensitivity)}" }
        val pinchSensSeek = SeekBar(this).apply {
            max = 100
            progress = ((settings.pinchSensitivity - 5f) / 55f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    pinchSensLabel.text = "Multiplier: ${"%.0f".format(5f + p / 100f * 55f)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(pinchSensSeek)
        layout.addView(pinchSensLabel)

        // Pinch threshold
        layout.addView(TextView(this).apply { text = "Pinch vs Scroll Threshold"; setPadding(0, 16, 0, 4) })
        val pinchThreshLabel = TextView(this).apply {
            text = "${"%.1f".format(settings.pinchThreshold * 100)}% (lower = easier to pinch, higher = easier to scroll)"
        }
        val pinchThreshSeek = SeekBar(this).apply {
            max = 100
            progress = ((settings.pinchThreshold - 0.005f) / 0.045f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.005f + p / 100f * 0.045f
                    pinchThreshLabel.text = "${"%.1f".format(v * 100)}% (lower = easier to pinch, higher = easier to scroll)"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(pinchThreshSeek)
        layout.addView(pinchThreshLabel)

        // Version info
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        layout.addView(TextView(this).apply {
            text = "TabletPen v$versionName"
            setPadding(0, 32, 0, 4)
            setTextColor(android.graphics.Color.GRAY)
            textSize = 12f
        })

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
                    mouseSensitivity = 0.5f + mouseSeek.progress / 100f * 4.5f,
                    scrollSensitivity = 0.5f + scrollSensSeek.progress / 100f * 4.5f,
                    pinchSensitivity = 5f + pinchSensSeek.progress / 100f * 55f,
                    pinchThreshold = 0.005f + pinchThreshSeek.progress / 100f * 0.045f,
                    cursorStyle = CursorStyle.entries[cursorSpinner.selectedItemPosition],
                    strokeColor = StrokeColor.entries[strokeSpinner.selectedItemPosition],
                    autoRecapture = recaptureCheck.isChecked,
                    showGhostStroke = ghostCheck.isChecked,
                    screenshotQuality = CaptureQuality.entries[ssQualitySpinner.selectedItemPosition],
                    streamMethod = StreamMethod.entries[streamMethodSpinner.selectedItemPosition],
                    streamQuality = CaptureQuality.entries[streamQualitySpinner.selectedItemPosition],
                    autoSwitchPreset = autoSwitchCheck.isChecked,
                    shortcuts = SHORTCUT_PRESETS[presetNames[presetSpinner.selectedItemPosition]] ?: DEFAULT_SHORTCUTS
                )
                AppSettings.save(this, settings)
                btScreenshot.screenshotQuality = settings.screenshotQuality
                btScreenshot.streamMethod = settings.streamMethod
                btScreenshot.streamQuality = settings.streamQuality
                applySettingsToDrawPad()
                setupShortcutButtons()
                applyOrientation()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Pinch zoom → Ctrl + scroll (zoom on laptop) ----

    private var ctrlHeld = false

    private var pinchAccum = 0f

    private fun handlePinchZoom(scaleFactor: Float) {
        if (!hidManager.isConnected) return

        // Press Ctrl if not already held
        if (!ctrlHeld) {
            hidManager.sendKeyboardReport(HidDescriptor.MOD_LEFT_CTRL)
            ctrlHeld = true
        }

        // Accumulate scale changes for smooth zooming
        // Reversed: fingers together = zoom in, fingers apart = zoom out
        pinchAccum += -(scaleFactor - 1f) * settings.pinchSensitivity

        val scroll = pinchAccum.toInt()
        if (scroll != 0) {
            hidManager.sendMouseReport(false, false, false, 0, 0, scroll.coerceIn(-20, 20))
            pinchAccum -= scroll
        }

        // Release Ctrl after a delay (will be re-pressed if pinch continues)
        drawPad.removeCallbacks(ctrlReleaseRunnable)
        drawPad.postDelayed(ctrlReleaseRunnable, 200)
    }

    private val ctrlReleaseRunnable = Runnable {
        if (ctrlHeld) {
            hidManager.sendKeyboardReport(0)
            ctrlHeld = false
            pinchAccum = 0f
        }
    }

    // ---- Auto-detect Mac foreground app ----

    @SuppressLint("SetTextI18n")
    private fun handleAppDetected(appName: String) {
        if (!settings.autoSwitchPreset) return
        if (appName == detectedApp) return
        detectedApp = appName

        val presetName = APP_PRESET_MAP[appName]
        if (presetName != null) {
            val preset = SHORTCUT_PRESETS[presetName] ?: return
            val pressureOverride = APP_PRESSURE_MAP[appName]

            settings = settings.copy(
                shortcuts = preset,
                pressureFloor = pressureOverride ?: settings.pressureFloor
            )
            AppSettings.save(this, settings)
            applySettingsToDrawPad()
            setupShortcutButtons()
            android.widget.Toast.makeText(this, "→ $presetName", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.i("AppDetect", "Auto-switched to $presetName preset for $appName")
        }
    }

    // ---- Focus ----

    @SuppressLint("SetTextI18n")
    private fun onFocusClicked() {
        if (drawPad.focusRect != null) {
            drawPad.resetFocus()
            btScreenshot.focusRect = null
            focusBtn.text = "Focus"
        } else if (drawPad.focusSelecting) {
            drawPad.focusSelecting = false
            focusBtn.text = "Focus"
        } else {
            drawPad.focusSelecting = true
            focusBtn.text = "Reset Focus"
        }
    }

    // ---- Screenshot ----

    @SuppressLint("SetTextI18n")
    private fun onScreenshotClicked() {
        if (!btScreenshot.isMacConnected) {
            android.widget.Toast.makeText(this, "Mac not connected. Run ./screenshot-server on Mac.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        screenshotBtn.text = "Capturing..."
        screenshotBtn.isEnabled = false
        btScreenshot.requestScreenshot()
    }

    @SuppressLint("SetTextI18n")
    private fun onStreamClicked() {
        if (btScreenshot.isStreaming) {
            btScreenshot.stopStream()
            streamBtn.text = "Stream"
        } else {
            btScreenshot.startStream()
            streamBtn.text = "Stop"
        }
    }

    // ---- Bluetooth HID listener ----

    override fun onHidReady(registered: Boolean) {
        hidRegistered = registered
        runOnUiThread { updateUI() }
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceConnected(device: BluetoothDevice) {
        val address = device.address
        runOnUiThread {
            AppSettings.saveLastDevice(this, address)
            hidManager.setAutoConnectDevice(address)
            // Tie screenshot RFCOMM + WiFi to the same host
            btScreenshot.setTargetDevice(address)
            refreshDeviceSpinner()
            updateUI()
        }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread {
            refreshDeviceSpinner()
            updateUI()
        }
    }

    override fun onError(message: String) {
        runOnUiThread { updateUI() }
    }

    // ---- Device management ----

    /** Get bonded devices that are eligible HID hosts (computers, phones, uncategorized) */
    @SuppressLint("MissingPermission")
    private fun getEligibleDevices(): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            ?: return result
        try {
            for (device in adapter.bondedDevices) {
                val major = device.bluetoothClass?.majorDeviceClass ?: 0
                // Include computers, phones, and uncategorized (some Macs report oddly)
                if (major == BluetoothClass.Device.Major.COMPUTER ||
                    major == BluetoothClass.Device.Major.PHONE ||
                    major == BluetoothClass.Device.Major.UNCATEGORIZED ||
                    major == BluetoothClass.Device.Major.MISC) {
                    val name = try { device.name } catch (_: Exception) { null } ?: "Unknown"
                    result[device.address] = name
                }
            }
        } catch (_: SecurityException) {}
        return result
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceSpinner() {
        deviceNames = getEligibleDevices()

        // Always include the currently connected device even if class doesn't match
        val connectedAddr = hidManager.connectedDeviceAddress
        val connectedName = hidManager.connectedDeviceName
        if (connectedAddr != null && connectedAddr !in deviceNames) {
            deviceNames[connectedAddr] = connectedName ?: "Unknown"
        }

        deviceAddresses = deviceNames.keys.toList()
        val modeName = if (settings.inputMode == InputMode.DIGITIZER) "Pen" else "Mouse"

        val labels = deviceAddresses.map { addr ->
            val name = deviceNames[addr] ?: "Unknown"
            if (addr == connectedAddr) "$name [$modeName]" else name
        }.ifEmpty { listOf("No computers paired") }

        suppressSpinnerEvent = true
        deviceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        val target = connectedAddr ?: AppSettings.loadLastDevice(this)
        val idx = deviceAddresses.indexOf(target)
        if (idx >= 0) {
            deviceSpinner.setSelection(idx)
        }
    }

    /** Unified connect: HID + Screenshot target the same device */
    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        AppSettings.saveLastDevice(this, address)
        hidManager.setAutoConnectDevice(address)
        hidManager.disconnect()
        hidManager.connectTo(address)
        // Screenshot will be tied to this device in onDeviceConnected callback
    }

    // ---- UI ----

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun updateUI() {
        // Discoverable / connect button
        if (!hidRegistered) {
            val dots = ".".repeat(dotCount)
            discoverableBtn.text = "Registering$dots"
            discoverableBtn.isEnabled = false
            discoverableBtn.visibility = View.VISIBLE
            startDotAnimation()
        } else if (hidManager.isConnected) {
            discoverableBtn.visibility = View.GONE
            stopDotAnimation()
        } else {
            discoverableBtn.text = "Pair"
            discoverableBtn.isEnabled = true
            discoverableBtn.visibility = View.VISIBLE
            stopDotAnimation()
        }

        // Stream button — visible when WiFi or BT connected
        if (btScreenshot.isWifiConnected || btScreenshot.isBtConnected) {
            streamBtn.visibility = View.VISIBLE
        }

        // Screenshot button — shows Mac connection state
        updateScreenshotBtn()
    }

    @SuppressLint("SetTextI18n")
    private fun updateScreenshotBtn() {
        if (!screenshotBtn.isEnabled) return  // currently capturing
        screenshotBtn.text = "Screenshot"
        screenshotBtn.isEnabled = true
    }

    private fun startDotAnimation() {
        if (!dotAnimRunning) {
            dotAnimRunning = true
            uiHandler.postDelayed(dotAnimRunnable, 400)
        }
    }

    private fun stopDotAnimation() {
        if (dotAnimRunning) {
            dotAnimRunning = false
            uiHandler.removeCallbacks(dotAnimRunnable)
            dotCount = 0
        }
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
                discoverableBtn.text = "No BT Permission"
                discoverableBtn.isEnabled = false
                discoverableBtn.visibility = View.VISIBLE
            }
        }
    }
}
