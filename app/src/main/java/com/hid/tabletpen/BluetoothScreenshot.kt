package com.hid.tabletpen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Screenshot service supporting Bluetooth RFCOMM and WiFi TCP (bidirectional).
 *
 * WiFi connection tries both directions:
 * 1. Android connects to Mac's TCP server (Mac-as-server, preferred)
 * 2. If that fails, Android starts TCP server and Mac connects to it (tablet-as-server, fallback)
 * Whichever succeeds first is used. Prefers Mac-as-server.
 */
@SuppressLint("MissingPermission")
class BluetoothScreenshot(private val context: Context) {

    data class DeltaTile(val x: Int, val y: Int, val w: Int, val h: Int, val jpeg: ByteArray)

    companion object {
        private const val TAG = "BtScreenshot"
        private val SERVICE_UUID = UUID.fromString("A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7")
        private const val SERVICE_NAME = "TabletPen Screenshot"
        private const val WIFI_CONNECT_TIMEOUT = 3000
        private const val TABLET_SERVER_PORT = 9878
    }

    interface Listener {
        fun onScreenshotReceived(bitmap: Bitmap)
        fun onScreenshotError(message: String)
        fun onWifiStateChanged(connected: Boolean)
        fun onStreamFrame(bitmap: Bitmap)
        fun onDeltaFrame(tiles: List<DeltaTile>)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Bluetooth RFCOMM
    private val running = AtomicBoolean(false)
    private val connectedSocket = AtomicReference<BluetoothSocket?>(null)
    @Volatile private var currentServerSocket: BluetoothServerSocket? = null

    // Detect BT disable to unblock accept()
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    Log.i(TAG, "BT turning off — closing server socket to unblock accept()")
                    try { currentServerSocket?.close() } catch (_: Exception) {}
                    currentServerSocket = null
                }
            }
        }
    }

    val isMacConnected: Boolean get() = connectedSocket.get()?.isConnected == true || isWifiConnected

    // WiFi TCP
    @Volatile private var wifiSocket: Socket? = null
    private var wifiHost: String? = null
    private var wifiPort: Int = 0
    val isWifiConnected: Boolean get() = wifiSocket?.isConnected == true

    // Tablet TCP server (fallback when Mac-as-server is blocked)
    private var tabletServer: ServerSocket? = null

    // Target device — only accept RFCOMM from this address (tied to HID device)
    @Volatile private var targetDeviceAddress: String? = null

    fun setTargetDevice(address: String?) {
        targetDeviceAddress = address
        Log.i(TAG, "Screenshot target device: $address")
    }

    // Streaming
    private val streaming = AtomicBoolean(false)
    val isStreaming: Boolean get() = streaming.get()

    // Guards BT socket reads
    private val btReadBusy = AtomicBoolean(false)

    // Focus rect for region capture (normalized 0-1, set from MainActivity)
    @Volatile var focusRect: RectF? = null

    // Transfer speed tracking for adaptive quality
    @Volatile private var lastTransferMs: Long = 0
    @Volatile private var lastTransferBytes: Int = 0

    private fun buildCommand(base: String): String {
        val (quality, maxDim) = PenMath.computeAdaptiveQuality(lastTransferMs, lastTransferBytes)
        val sb = StringBuilder(base)
        sb.append(" q=$quality max=$maxDim")
        focusRect?.let { r ->
            sb.append(" r=%.3f,%.3f,%.3f,%.3f".format(r.left, r.top, r.right, r.bottom))
        }
        sb.append("\n")
        return sb.toString()
    }

    fun startServer() {
        if (running.getAndSet(true)) return
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        Thread({ serverLoop() }, "BtScreenshot-Server").start()
    }

    fun stopServer() {
        running.set(false)
        try { context.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        stopStream()
        try { wifiSocket?.close() } catch (_: Exception) {}
        try { tabletServer?.close() } catch (_: Exception) {}
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
                currentServerSocket = server

                while (running.get()) {
                    Log.i(TAG, "Waiting for Mac to connect...")
                    val socket = server.accept()
                    val remoteAddr = socket.remoteDevice?.address
                    val remoteName = try { socket.remoteDevice?.name } catch (_: Exception) { null }

                    // Filter: only accept from the HID-targeted device
                    val target = targetDeviceAddress
                    if (target != null && remoteAddr != null && !remoteAddr.equals(target, ignoreCase = true)) {
                        Log.i(TAG, "Rejected RFCOMM from $remoteName ($remoteAddr) — expecting $target")
                        try { socket.close() } catch (_: Exception) {}
                        continue
                    }

                    connectedSocket.set(socket)
                    Log.i(TAG, "Mac connected via BT: $remoteName ($remoteAddr)")

                    readWifiInfoAndConnect(socket)

                    // Keep alive — wait for disconnect
                    try {
                        while (running.get() && socket.isConnected) {
                            Thread.sleep(1000)
                        }
                    } catch (_: Exception) {}

                    Log.i(TAG, "Mac disconnected — ready for reconnect")
                    connectedSocket.set(null)
                    disconnectWifi()
                }

                currentServerSocket = null
                server.close()
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Server error: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }
    }

    // MARK: - WiFi: Bidirectional Connection

    private fun readWifiInfoAndConnect(btSocket: BluetoothSocket) {
        btReadBusy.set(true)
        val readerThread = Thread({
            try {
                val input = btSocket.inputStream
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    sb.append(b.toChar())
                }
                val line = sb.toString().trim()
                Log.i(TAG, "Mac sent: '$line'")
                val wifiInfo = PenMath.parseWifiInfo(line)
                if (wifiInfo != null) {
                    wifiHost = wifiInfo.first
                    wifiPort = wifiInfo.second
                    Log.i(TAG, "WiFi info received: $wifiHost:$wifiPort")
                    tryBothDirections(btSocket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read WiFi info: ${e.message}")
            } finally {
                btReadBusy.set(false)
            }
        }, "BT-WifiInfo")
        readerThread.start()
        readerThread.join(5000)
        // Always clear the busy flag after timeout — allow BT screenshots
        // even if WiFi info hasn't arrived yet. The reader thread continues
        // in background and will connect WiFi when data arrives.
        btReadBusy.set(false)
    }

    /**
     * Try both WiFi directions simultaneously:
     * 1. Connect to Mac's TCP server (preferred)
     * 2. Start our own TCP server and tell Mac to connect to us (fallback)
     * First one to succeed wins.
     */
    private fun tryBothDirections(btSocket: BluetoothSocket) {
        val connected = AtomicBoolean(false)

        // Direction 1: Connect to Mac (preferred)
        val clientThread = Thread({
            try {
                val host = wifiHost ?: return@Thread
                val port = wifiPort
                Log.i(TAG, "WiFi trying Mac-as-server: $host:$port...")
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), WIFI_CONNECT_TIMEOUT)
                sock.tcpNoDelay = true
                if (connected.compareAndSet(false, true)) {
                    wifiSocket = sock
                    Log.i(TAG, "WiFi connected (Mac-as-server): $host:$port")
                    stopTabletServer()
                    mainHandler.post { listener?.onWifiStateChanged(true) }
                } else {
                    sock.close() // Other direction won
                }
            } catch (e: Exception) {
                Log.i(TAG, "WiFi Mac-as-server failed: ${e.message}")
            }
        }, "WiFi-Client")

        // Direction 2: Start tablet server, tell Mac to connect to us
        val serverThread = Thread({
            try {
                // Start TCP server
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(TABLET_SERVER_PORT))
                tabletServer = srv
                Log.i(TAG, "WiFi tablet server listening on port $TABLET_SERVER_PORT")

                // Tell Mac our IP and port over BT
                val tabletIP = getLocalIP()
                if (tabletIP != null) {
                    val msg = "wifiserver:$tabletIP:$TABLET_SERVER_PORT\n"
                    synchronized(btSocket) {
                        btSocket.outputStream.write(msg.toByteArray())
                        btSocket.outputStream.flush()
                    }
                    Log.i(TAG, "Sent reverse WiFi info to Mac: $msg".trim())
                } else {
                    Log.w(TAG, "Could not determine tablet IP")
                    srv.close()
                    return@Thread
                }

                // Wait for Mac to connect (10s timeout)
                srv.soTimeout = 10000
                val sock = srv.accept()
                sock.tcpNoDelay = true
                if (connected.compareAndSet(false, true)) {
                    wifiSocket = sock
                    Log.i(TAG, "WiFi connected (tablet-as-server)")
                    mainHandler.post { listener?.onWifiStateChanged(true) }
                } else {
                    sock.close() // Other direction won
                }
                srv.close()
                tabletServer = null
            } catch (e: Exception) {
                if (!connected.get()) {
                    Log.i(TAG, "WiFi tablet-as-server failed: ${e.message}")
                }
                try { tabletServer?.close() } catch (_: Exception) {}
                tabletServer = null
            }
        }, "WiFi-Server")

        clientThread.start()
        serverThread.start()

        // Wait for either to succeed
        Thread({
            clientThread.join(12000)
            serverThread.join(12000)
            if (!connected.get()) {
                Log.w(TAG, "WiFi failed in both directions — screenshots will use BT")
                mainHandler.post { listener?.onWifiStateChanged(false) }
            }
        }, "WiFi-Wait").start()
    }

    private fun getLocalIP(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun stopTabletServer() {
        try { tabletServer?.close() } catch (_: Exception) {}
        tabletServer = null
    }

    private fun disconnectWifi() {
        try { wifiSocket?.close() } catch (_: Exception) {}
        wifiSocket = null
        stopTabletServer()
        streaming.set(false)
        mainHandler.post { listener?.onWifiStateChanged(false) }
    }

    // MARK: - Screenshot Request

    fun requestScreenshot() {
        val wifi = wifiSocket
        if (wifi != null && wifi.isConnected) {
            Thread({ doRequestWifi(wifi) }, "Screenshot-WiFi").start()
        } else if (btReadBusy.get()) {
            mainHandler.post { listener?.onScreenshotError("Connecting to Mac... try again") }
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
                val cmd = buildCommand("screenshot")
                socket.outputStream.write(cmd.toByteArray())
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

                lastTransferMs = t2 - t1
                lastTransferBytes = size
                Log.i(TAG, "BT ${bitmap.width}x${bitmap.height} ${size/1024}KB — wait:${t1-t0}ms recv:${t2-t1}ms decode:${t3-t2}ms total:${t3-t0}ms")
                mainHandler.post { listener?.onScreenshotReceived(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "BT screenshot failed", e)
                // Socket is dead (broken pipe after sleep) — close it so
                // the server loop detects disconnect and accepts a new connection
                try { socket.close() } catch (_: Exception) {}
                connectedSocket.set(null)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "BT error") }
            }
        }
    }

    private fun doRequestWifi(socket: Socket) {
        synchronized(socket) {
            try {
                val t0 = System.currentTimeMillis()
                val cmd = buildCommand("screenshot")
                socket.getOutputStream().write(cmd.toByteArray())
                socket.getOutputStream().flush()

                val frame = readFrame(socket.getInputStream())
                val t1 = System.currentTimeMillis()
                val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    ?: throw Exception("Failed to decode JPEG")
                val t2 = System.currentTimeMillis()

                lastTransferMs = t1 - t0
                lastTransferBytes = frame.size
                Log.i(TAG, "WiFi ${bitmap.width}x${bitmap.height} ${frame.size/1024}KB — transfer:${t1-t0}ms decode:${t2-t1}ms total:${t2-t0}ms")
                mainHandler.post { listener?.onScreenshotReceived(bitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "WiFi screenshot failed", e)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "WiFi error") }
                disconnectWifi()
                // Trigger reconnection for next request
                connectedSocket.get()?.let { tryBothDirections(it) }
            }
        }
    }

    // MARK: - Streaming

    fun startStream() {
        val host = wifiHost
        val port = wifiPort
        if (host == null || port <= 0) {
            mainHandler.post { listener?.onScreenshotError("WiFi not available") }
            return
        }
        if (streaming.getAndSet(true)) return
        // Open a FRESH TCP connection for streaming (separate from screenshot socket)
        // This avoids socket state confusion when switching from request-response to push mode
        Thread({
            try {
                Log.i(TAG, "Opening fresh WiFi connection for streaming...")
                val streamSocket = java.net.Socket()
                streamSocket.connect(java.net.InetSocketAddress(host, port), WIFI_CONNECT_TIMEOUT)
                streamSocket.tcpNoDelay = true
                Log.i(TAG, "Stream WiFi connected to $host:$port")
                streamLoop(streamSocket)
                streamSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Stream WiFi connect failed: ${e.message}")
                streaming.set(false)
                mainHandler.post { listener?.onScreenshotError("Stream failed: ${e.message}") }
            }
        }, "Stream-WiFi").start()
    }

    // Stream socket is separate from wifiSocket (screenshot socket)
    @Volatile private var streamSocket: java.net.Socket? = null

    fun stopStream() {
        if (!streaming.getAndSet(false)) return
        try {
            streamSocket?.getOutputStream()?.write("stop\n".toByteArray())
            streamSocket?.getOutputStream()?.flush()
        } catch (_: Exception) {}
        try { streamSocket?.close() } catch (_: Exception) {}
        streamSocket = null
    }

    private fun streamLoop(socket: java.net.Socket) {
        streamSocket = socket
        try {
            val cmd = buildCommand("stream")
            socket.getOutputStream().write(cmd.toByteArray())
            socket.getOutputStream().flush()

            val input = socket.getInputStream()
            var frameCount = 0
            val startTime = System.currentTimeMillis()

            while (streaming.get()) {
                val t0 = System.currentTimeMillis()

                // Read frame type byte
                val type = input.read()
                if (type < 0) break

                when (type) {
                    0x00, 0x02 -> {
                        // Full frame or key frame
                        val frame = readFrame(input)
                        val t1 = System.currentTimeMillis()
                        val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size) ?: continue
                        val t2 = System.currentTimeMillis()
                        frameCount++
                        val elapsed = (t2 - startTime) / 1000.0
                        val fps = if (elapsed > 0) frameCount / elapsed else 0.0
                        val label = if (type == 0x02) "key" else "full"
                        Log.d(TAG, "Stream [$label] #$frameCount ${frame.size/1024}KB recv:${t1-t0}ms decode:${t2-t1}ms fps:${"%.1f".format(fps)}")
                        mainHandler.post { listener?.onStreamFrame(bitmap) }
                    }
                    0x01 -> {
                        // Delta frame
                        val sizeBytes = readExact(input, 4)
                        val totalSize = ByteBuffer.wrap(sizeBytes).int
                        if (totalSize <= 0 || totalSize > 20 * 1024 * 1024) continue

                        val payload = readExact(input, totalSize)
                        val t1 = System.currentTimeMillis()

                        // Parse delta tiles
                        val buf = ByteBuffer.wrap(payload)
                        val tileCount = buf.short.toInt() and 0xFFFF
                        val tiles = mutableListOf<DeltaTile>()
                        for (i in 0 until tileCount) {
                            val tx = buf.short.toInt() and 0xFFFF
                            val ty = buf.short.toInt() and 0xFFFF
                            val tw = buf.short.toInt() and 0xFFFF
                            val th = buf.short.toInt() and 0xFFFF
                            val jpegSize = buf.int
                            val jpeg = ByteArray(jpegSize)
                            buf.get(jpeg)
                            tiles.add(DeltaTile(tx, ty, tw, th, jpeg))
                        }

                        val t2 = System.currentTimeMillis()
                        frameCount++
                        val elapsed = (t2 - startTime) / 1000.0
                        val fps = if (elapsed > 0) frameCount / elapsed else 0.0
                        Log.d(TAG, "Stream [delta] #$frameCount ${tileCount} tiles ${totalSize/1024}KB recv:${t1-t0}ms parse:${t2-t1}ms fps:${"%.1f".format(fps)}")
                        mainHandler.post { listener?.onDeltaFrame(tiles) }
                    }
                    else -> {
                        Log.w(TAG, "Unknown frame type: $type")
                    }
                }
            }
        } catch (e: Exception) {
            if (streaming.get()) {
                Log.e(TAG, "Stream error: ${e.message}")
                streaming.set(false)
                mainHandler.post { listener?.onScreenshotError("Stream ended: ${e.message}") }
            }
        } finally {
            streamSocket = null
        }
    }

    // MARK: - Protocol helpers

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
