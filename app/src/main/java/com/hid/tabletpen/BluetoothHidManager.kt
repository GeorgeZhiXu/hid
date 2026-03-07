package com.hid.tabletpen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BtHidManager"
    }

    interface Listener {
        fun onHidReady(registered: Boolean)
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected()
        fun onError(message: String)
    }

    var listener: Listener? = null

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = connectedDevice != null
    val connectedDeviceName: String? get() = connectedDevice?.name
    val connectedDeviceAddress: String? get() = connectedDevice?.address

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var autoConnectAddress: String? = null

    fun setAutoConnectDevice(address: String?) {
        autoConnectAddress = address
    }

    fun start() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            listener?.onError("Bluetooth is not available or not enabled")
            return
        }
        bt.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    /** Attempt reconnection if not connected — safe to call anytime */
    fun ensureConnected() {
        if (connectedDevice != null) return // already connected
        if (hidDevice != null) {
            // Profile proxy alive — just try to reconnect
            Log.i(TAG, "Trying auto-connect...")
            tryAutoConnect()
        } else {
            // Profile proxy lost (e.g., after deep sleep) — re-acquire
            Log.i(TAG, "Re-acquiring HID profile proxy...")
            start()
        }
    }

    /** Disconnect current device (if any) */
    fun disconnect() {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return
        try {
            hid.disconnect(device)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
    }

    /** Connect to a specific bonded device by address */
    fun connectTo(address: String) {
        val bt = adapter ?: return
        val hid = hidDevice ?: return
        try {
            val device = bt.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "Connecting to ${device.name ?: address}")
                hid.connect(device)
            } else {
                Log.w(TAG, "Device $address not bonded")
                listener?.onError("Device not paired: ${device.name ?: address}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "connectTo failed", e)
            listener?.onError("Connection failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp failed", e)
        }
        hidDevice = null
        connectedDevice = null
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }

    fun sendDigitizerReport(
        tipDown: Boolean,
        barrel: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int,
        eraser: Boolean = false,
        tiltX: Int = 0,
        tiltY: Int = 0
    ): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptor.buildReport(tipDown, barrel, inRange, x, y, pressure, eraser, tiltX, tiltY)
        return hid.sendReport(device, HidDescriptor.REPORT_ID_DIGITIZER, report)
    }

    fun sendMouseReport(
        left: Boolean,
        right: Boolean,
        middle: Boolean,
        dx: Int,
        dy: Int,
        scroll: Int = 0
    ): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptor.buildMouseReport(left, right, middle, dx, dy, scroll)
        return hid.sendReport(device, HidDescriptor.REPORT_ID_MOUSE, report)
    }

    fun sendKeyboardReport(modifiers: Int, vararg keycodes: Int): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptor.buildKeyboardReport(modifiers, *keycodes)
        return hid.sendReport(device, HidDescriptor.REPORT_ID_KEYBOARD, report)
    }

    // ---- Profile listener ----

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.d(TAG, "HID_DEVICE profile connected")
            hidDevice = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID_DEVICE profile disconnected")
            hidDevice = null
            connectedDevice = null
            listener?.onDeviceDisconnected()
        }
    }

    // ---- App registration ----

    private fun registerApp() {
        val hid = hidDevice ?: return

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TabletPen",
            "Stylus-to-HID digitizer bridge",
            "HID",
            BluetoothHidDevice.SUBCLASS1_NONE,
            HidDescriptor.DESCRIPTOR
        )

        // QoS not strictly required; pass null for default
        hid.registerApp(sdp, null, null, executor, hidCallback)
    }

    // ---- HID callback ----

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged registered=$registered device=$pluggedDevice")
            listener?.onHidReady(registered)
            if (registered && connectedDevice == null) {
                tryAutoConnect()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged device=${device.name} state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    listener?.onDeviceConnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    listener?.onDeviceDisconnected()
                    // Auto-reconnect after a short delay
                    executor.execute {
                        try { Thread.sleep(500) } catch (_: Exception) {}
                        Log.i(TAG, "Attempting auto-reconnect after disconnect...")
                        tryAutoConnect()
                    }
                }
            }
        }

        override fun onGetReport(
            device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int
        ) {
            Log.d(TAG, "onGetReport type=$type id=$id")
            // Respond with a zero report
            when (id.toInt()) {
                HidDescriptor.REPORT_ID_DIGITIZER -> {
                    hidDevice?.replyReport(device, type, id, ByteArray(HidDescriptor.DIGITIZER_REPORT_SIZE))
                }
                HidDescriptor.REPORT_ID_MOUSE -> {
                    hidDevice?.replyReport(device, type, id, ByteArray(HidDescriptor.MOUSE_REPORT_SIZE))
                }
                HidDescriptor.REPORT_ID_KEYBOARD -> {
                    hidDevice?.replyReport(device, type, id, ByteArray(HidDescriptor.KEYBOARD_REPORT_SIZE))
                }
                else -> {
                    hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            }
        }

        override fun onSetReport(
            device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray
        ) {
            Log.d(TAG, "onSetReport type=$type id=$id")
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            Log.d(TAG, "onInterruptData id=$reportId len=${data.size}")
        }
    }

    private fun tryAutoConnect() {
        val address = autoConnectAddress ?: return
        val bt = adapter ?: return
        val hid = hidDevice ?: return

        try {
            // Verify device is actually in the bonded devices set (not just stale remote object)
            val bonded = bt.bondedDevices?.any { it.address.equals(address, ignoreCase = true) } ?: false
            if (!bonded) {
                Log.d(TAG, "Device $address not in bonded devices, skipping auto-connect")
                return
            }
            val device = bt.getRemoteDevice(address)
            Log.i(TAG, "Auto-connecting to ${device.name ?: address}")
            hid.connect(device)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-connect failed", e)
        }
    }
}
