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

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    fun start() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            listener?.onError("Bluetooth is not available or not enabled")
            return
        }
        bt.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
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
        pressure: Int
    ): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        val report = HidDescriptor.buildReport(tipDown, barrel, inRange, x, y, pressure)
        return hid.sendReport(device, HidDescriptor.REPORT_ID_DIGITIZER, report)
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
            BluetoothHidDevice.SUBCLASS2_DIGITIZER_TABLET,
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
                }
            }
        }

        override fun onGetReport(
            device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int
        ) {
            Log.d(TAG, "onGetReport type=$type id=$id")
            // Respond with a zero report
            if (id.toInt() == HidDescriptor.REPORT_ID_DIGITIZER) {
                hidDevice?.replyReport(
                    device, type, id,
                    ByteArray(HidDescriptor.DIGITIZER_REPORT_SIZE)
                )
            } else {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
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
}
