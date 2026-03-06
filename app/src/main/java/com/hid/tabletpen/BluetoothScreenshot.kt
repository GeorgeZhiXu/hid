package com.hid.tabletpen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Screenshot service supporting both Bluetooth RFCOMM and WiFi TCP.
 *
 * Flow:
 * 1. Android runs RFCOMM server → Mac connects
 * 2. Mac sends "wifi:<ip>:<port>\n" over RFCOMM
 * 3. Android connects to Mac's TCP server over WiFi
 * 4. Screenshots/streaming use WiFi when available, BT as fallback
 */
@SuppressLint("MissingPermission")
class BluetoothScreenshot(private val context: Context) {

    companion object {
        private const val TAG = "BtScreenshot"
        private val SERVICE_UUID = UUID.fromString("A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7")
        private const val SERVICE_NAME = "TabletPen Screenshot"
        private const val WIFI_CONNECT_TIMEOUT = 3000
    }

    interface Listener {
        fun onScreenshotReceived(bitmap: Bitmap)
        fun onScreenshotError(message: String)
        fun onWifiStateChanged(connected: Boolean)
        fun onStreamFrame(bitmap: Bitmap)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Bluetooth RFCOMM
    private val running = AtomicBoolean(false)
    private val connectedSocket = AtomicReference<BluetoothSocket?>(null)

    val isMacConnected: Boolean get() = connectedSocket.get()?.isConnected == true || isWifiConnected

    // WiFi TCP
    private var wifiSocket: Socket? = null
    private var wifiHost: String? = null
    private var wifiPort: Int = 0
    val isWifiConnected: Boolean get() = wifiSocket?.isConnected == true

    // Streaming
    private val streaming = AtomicBoolean(false)
    val isStreaming: Boolean get() = streaming.get()

    fun startServer() {
        if (running.getAndSet(true)) return
        Thread({ serverLoop() }, "BtScreenshot-Server").start()
    }

    fun stopServer() {
        running.set(false)
        stopStream()
        try { wifiSocket?.close() } catch (_: Exception) {}
        try { connectedSocket.get()?.close() } catch (_: Exception) {}
    }

    // MARK: - RFCOMM Server

    private fun serverLoop() {
        val bt = adapter ?: return

        while (running.get()) {
            try {
                Log.i(TAG, "Starting RFCOMM server...")
                val server: BluetoothServerSocket =
                    bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)

                // Keep server socket open — accept multiple connections
                // without re-registering SDP record each time
                while (running.get()) {
                    Log.i(TAG, "Waiting for Mac to connect...")
                    val socket = server.accept()

                    connectedSocket.set(socket)
                    Log.i(TAG, "Mac connected via BT: ${socket.remoteDevice.name}")

                    readWifiInfo(socket)

                    // Keep alive — wait for disconnect
                    try {
                        while (running.get() && socket.isConnected) {
                            Thread.sleep(1000)
                        }
                    } catch (_: Exception) {}

                    Log.i(TAG, "Mac disconnected — ready for reconnect")
                    connectedSocket.set(null)
                    disconnectWifi()
                    // Loop back to accept() immediately — server socket still open
                }

                server.close()
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Server error: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }
    }

    // MARK: - WiFi Discovery & Connection

    private fun readWifiInfo(btSocket: BluetoothSocket) {
        // Read WiFi info on a timeout thread (blocking read — available() is unreliable on BT)
        val readerThread = Thread({
            try {
                val input = btSocket.inputStream
                val sb = StringBuilder()
                // Blocking byte-by-byte read until newline (avoids buffering binary data)
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    sb.append(b.toChar())
                }
                val line = sb.toString().trim()
                Log.i(TAG, "Mac sent: '$line'")
                if (line.startsWith("wifi:")) {
                    val parts = line.removePrefix("wifi:").split(":")
                    if (parts.size == 2) {
                        wifiHost = parts[0]
                        wifiPort = parts[1].toIntOrNull() ?: 0
                        if (wifiPort > 0) {
                            Log.i(TAG, "WiFi info received: $wifiHost:$wifiPort")
                            connectWifi()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read WiFi info: ${e.message}")
            }
        }, "BT-WifiInfo")
        readerThread.start()
        // Wait up to 5 seconds for Mac to send WiFi info
        readerThread.join(5000)
        if (readerThread.isAlive) {
            Log.w(TAG, "WiFi info read timed out — Mac may not have sent it yet")
            // Thread will continue in background; if data arrives later it'll still connect
        }
    }

    private fun connectWifi() {
        val host = wifiHost ?: return
        val port = wifiPort
        Thread({
            try {
                Log.i(TAG, "Connecting WiFi to $host:$port...")
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), WIFI_CONNECT_TIMEOUT)
                sock.tcpNoDelay = true
                wifiSocket = sock
                Log.i(TAG, "WiFi connected to $host:$port")
                mainHandler.post { listener?.onWifiStateChanged(true) }
            } catch (e: Exception) {
                Log.w(TAG, "WiFi connect failed: ${e.message}")
                wifiSocket = null
                mainHandler.post { listener?.onWifiStateChanged(false) }
            }
        }, "WiFi-Connect").start()
    }

    private fun disconnectWifi() {
        try { wifiSocket?.close() } catch (_: Exception) {}
        wifiSocket = null
        streaming.set(false)
        mainHandler.post { listener?.onWifiStateChanged(false) }
    }

    // MARK: - Screenshot Request

    fun requestScreenshot() {
        val wifi = wifiSocket
        if (wifi != null && wifi.isConnected) {
            Thread({ doRequestWifi(wifi) }, "Screenshot-WiFi").start()
        } else {
            val bt = connectedSocket.get()
            if (bt == null || !bt.isConnected) {
                mainHandler.post { listener?.onScreenshotError("Mac not connected") }
                return
            }
            Thread({ doRequestBT(bt) }, "Screenshot-BT").start()
        }
    }

    private fun doRequestBT(socket: BluetoothSocket) {
        synchronized(socket) {
            try {
                val t0 = System.currentTimeMillis()
                socket.outputStream.write("screenshot\n".toByteArray())
                socket.outputStream.flush()

                val input = socket.inputStream
                val sizeBytes = readExact(input, 4)
                val size = ByteBuffer.wrap(sizeBytes).int
                val t1 = System.currentTimeMillis()

                if (size <= 0 || size > 20 * 1024 * 1024) throw Exception("Invalid size: $size")

                val jpegData = readExact(input, size)
                val t2 = System.currentTimeMillis()
                val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                    ?: throw Exception("Failed to decode JPEG")
                val t3 = System.currentTimeMillis()

                Log.i(TAG, "BT ${bitmap.width}x${bitmap.height} ${size/1024}KB — wait:${t1-t0}ms recv:${t2-t1}ms decode:${t3-t2}ms total:${t3-t0}ms")
                mainHandler.post { listener?.onScreenshotReceived(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "BT screenshot failed", e)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun doRequestWifi(socket: Socket) {
        synchronized(socket) {
            try {
                val t0 = System.currentTimeMillis()
                socket.getOutputStream().write("screenshot\n".toByteArray())
                socket.getOutputStream().flush()

                val frame = readFrame(socket.getInputStream())
                val t1 = System.currentTimeMillis()
                val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    ?: throw Exception("Failed to decode JPEG")
                val t2 = System.currentTimeMillis()

                Log.i(TAG, "WiFi ${bitmap.width}x${bitmap.height} ${frame.size/1024}KB — transfer:${t1-t0}ms decode:${t2-t1}ms total:${t2-t0}ms")
                mainHandler.post { listener?.onScreenshotReceived(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "WiFi screenshot failed", e)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "WiFi error") }
                // WiFi broken, try to reconnect
                disconnectWifi()
                connectWifi()
            }
        }
    }

    // MARK: - Streaming

    fun startStream() {
        val wifi = wifiSocket
        if (wifi == null || !wifi.isConnected) {
            mainHandler.post { listener?.onScreenshotError("WiFi not connected") }
            return
        }
        if (streaming.getAndSet(true)) return
        Thread({ streamLoop(wifi) }, "Stream-WiFi").start()
    }

    fun stopStream() {
        if (!streaming.getAndSet(false)) return
        // Send stop command if WiFi is still connected
        try {
            wifiSocket?.getOutputStream()?.write("stop\n".toByteArray())
            wifiSocket?.getOutputStream()?.flush()
        } catch (_: Exception) {}
        // Reconnect WiFi for future single screenshots
        disconnectWifi()
        connectWifi()
    }

    private fun streamLoop(socket: Socket) {
        try {
            socket.getOutputStream().write("stream\n".toByteArray())
            socket.getOutputStream().flush()

            val input = socket.getInputStream()
            var frameCount = 0
            val startTime = System.currentTimeMillis()

            while (streaming.get()) {
                val t0 = System.currentTimeMillis()
                val frame = readFrame(input)
                val t1 = System.currentTimeMillis()
                val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    ?: continue
                val t2 = System.currentTimeMillis()
                frameCount++

                val elapsed = (t2 - startTime) / 1000.0
                val fps = if (elapsed > 0) frameCount / elapsed else 0.0
                Log.d(TAG, "Stream frame #$frameCount ${frame.size/1024}KB recv:${t1-t0}ms decode:${t2-t1}ms fps:${"%.1f".format(fps)}")

                mainHandler.post { listener?.onStreamFrame(bitmap) }
            }
        } catch (e: Exception) {
            if (streaming.get()) {
                Log.e(TAG, "Stream error: ${e.message}")
                streaming.set(false)
                mainHandler.post { listener?.onScreenshotError("Stream ended: ${e.message}") }
            }
        }
    }

    // MARK: - Protocol helpers

    /** Read [4-byte BE size][data] frame */
    private fun readFrame(input: InputStream): ByteArray {
        val sizeBytes = readExact(input, 4)
        val size = ByteBuffer.wrap(sizeBytes).int
        if (size <= 0 || size > 20 * 1024 * 1024) throw Exception("Invalid frame size: $size")
        return readExact(input, size)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n <= 0) throw Exception("Stream ended after $offset/$count bytes")
            offset += n
        }
        return buf
    }
}
