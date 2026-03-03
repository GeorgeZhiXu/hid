package com.hid.tabletpen

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BluetoothHidManager.Listener {

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_DISCOVERABLE = 2
    }

    private lateinit var hidManager: BluetoothHidManager
    private lateinit var statusText: TextView
    private lateinit var connectionText: TextView
    private lateinit var drawPad: DrawPadView
    private lateinit var discoverableBtn: Button
    private lateinit var clearBtn: Button

    private var hidRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on while active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.status_text)
        connectionText = findViewById(R.id.connection_text)
        drawPad = findViewById(R.id.draw_pad)
        discoverableBtn = findViewById(R.id.btn_discoverable)
        clearBtn = findViewById(R.id.btn_clear)

        hidManager = BluetoothHidManager(this)
        hidManager.listener = this

        drawPad.onPenEvent = { event -> handlePenEvent(event) }

        discoverableBtn.setOnClickListener { requestDiscoverable() }
        clearBtn.setOnClickListener { drawPad.clearStrokes() }

        updateUI()

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

    // ---- Pen event → BT HID report ----

    private fun handlePenEvent(event: DrawPadView.PenEvent) {
        if (!hidManager.isConnected) return

        val x = (event.normalizedX * HidDescriptor.X_MAX).toInt()
        val y = (event.normalizedY * HidDescriptor.Y_MAX).toInt()
        val pressure = (event.pressure * HidDescriptor.PRESSURE_MAX).toInt()

        hidManager.sendDigitizerReport(
            tipDown = event.tipDown,
            barrel = event.barrel,
            inRange = event.inRange,
            x = x,
            y = y,
            pressure = pressure
        )
    }

    // ---- Bluetooth HID listener ----

    override fun onHidReady(registered: Boolean) {
        hidRegistered = registered
        runOnUiThread { updateUI() }
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        runOnUiThread { updateUI() }
    }

    override fun onDeviceDisconnected() {
        runOnUiThread { updateUI() }
    }

    override fun onError(message: String) {
        runOnUiThread {
            statusText.text = "Error: $message"
        }
    }

    // ---- UI ----

    @SuppressLint("MissingPermission")
    private fun updateUI() {
        statusText.text = when {
            !hidRegistered -> "Registering HID device..."
            hidManager.isConnected -> "Connected — draw to send input"
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
