package com.hid.tabletpen

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.content.Intent
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

    private var hidRegistered = false

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

        btScreenshot = BluetoothScreenshot(this)
        btScreenshot.listener = object : BluetoothScreenshot.Listener {
            override fun onScreenshotReceived(bitmap: android.graphics.Bitmap) {
                drawPad.setScreenshot(bitmap)
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
                streamBtn.visibility = if (connected) View.VISIBLE else View.GONE
                if (!connected && btScreenshot.isStreaming) {
                    streamBtn.text = "Stream"
                }
            }
            override fun onStreamFrame(bitmap: android.graphics.Bitmap) {
                drawPad.setScreenshot(bitmap)
            }
        }
        btScreenshot.startServer()

        applySettingsToDrawPad()

        drawPad.onPenEvent = { event -> handlePenEvent(event) }
        drawPad.onMouseEvent = { event -> handleMouseEvent(event) }
        drawPad.onPinchZoom = { scaleFactor -> handlePinchZoom(scaleFactor) }

        discoverableBtn.setOnClickListener { requestDiscoverable() }
        clearBtn.setOnClickListener { drawPad.clearStrokes() }
        screenshotBtn.setOnClickListener { onScreenshotClicked() }
        streamBtn.setOnClickListener { onStreamClicked() }
        focusBtn.setOnClickListener { onFocusClicked() }
        settingsBtn.setOnClickListener { showSettingsDialog() }

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
                    strokeColor = StrokeColor.entries[strokeSpinner.selectedItemPosition]
                )
                AppSettings.save(this, settings)
                applySettingsToDrawPad()
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

    // ---- Focus ----

    @SuppressLint("SetTextI18n")
    private fun onFocusClicked() {
        if (drawPad.focusRect != null) {
            drawPad.resetFocus()
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
