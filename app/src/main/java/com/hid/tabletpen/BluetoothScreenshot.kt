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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bluetooth RFCOMM screenshot service.
 *
 * Android runs an RFCOMM server. Mac's screenshot-server connects to it.
 * When user taps Screenshot, Android sends "screenshot\n" command,
 * Mac takes screenshot and sends back 4-byte size + JPEG data.
 */
@SuppressLint("MissingPermission")
class BluetoothScreenshot(private val context: Context) {

    companion object {
        private const val TAG = "BtScreenshot"
        private val SERVICE_UUID = UUID.fromString("A5D3E9F0-7B1C-4C2E-9F3A-B8C1D2E4F6A7")
        private const val SERVICE_NAME = "TabletPen Screenshot"
    }

    interface Listener {
        fun onScreenshotReceived(bitmap: Bitmap)
        fun onScreenshotError(message: String)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val running = AtomicBoolean(false)
    private val connectedSocket = AtomicReference<BluetoothSocket?>(null)

    val isMacConnected: Boolean get() = connectedSocket.get()?.isConnected == true

    /**
     * Start the RFCOMM server. Call once after BT is ready.
     * Mac's screenshot-server will find and connect to us via SDP.
     */
    fun startServer() {
        if (running.getAndSet(true)) return
        Thread({ serverLoop() }, "BtScreenshot-Server").start()
    }

    fun stopServer() {
        running.set(false)
        try { connectedSocket.get()?.close() } catch (_: Exception) {}
    }

    private fun serverLoop() {
        val bt = adapter ?: return

        while (running.get()) {
            try {
                Log.i(TAG, "Starting RFCOMM server (UUID=$SERVICE_UUID)...")
                val server: BluetoothServerSocket =
                    bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)

                Log.i(TAG, "Waiting for Mac to connect...")
                val socket = server.accept()
                server.close()

                connectedSocket.set(socket)
                Log.i(TAG, "Mac connected: ${socket.remoteDevice.name}")
                mainHandler.post { listener?.onScreenshotError("Mac connected") }

                // Keep alive — wait for disconnect
                try {
                    val input = socket.inputStream
                    while (running.get() && socket.isConnected) {
                        // Check if socket is still alive by reading with timeout
                        Thread.sleep(1000)
                    }
                } catch (_: Exception) {}

                Log.i(TAG, "Mac disconnected")
                connectedSocket.set(null)
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Server error: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }
    }

    /**
     * Request a screenshot. Sends command over existing RFCOMM connection.
     */
    fun requestScreenshot() {
        val socket = connectedSocket.get()
        if (socket == null || !socket.isConnected) {
            mainHandler.post { listener?.onScreenshotError("Mac not connected. Run screenshot-server on Mac.") }
            return
        }

        Thread({
            try {
                doRequest(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "Unknown error") }
            }
        }, "BtScreenshot-Req").start()
    }

    private fun doRequest(socket: BluetoothSocket) {
        synchronized(socket) {
            val t0 = System.currentTimeMillis()

            socket.outputStream.write("screenshot\n".toByteArray())
            socket.outputStream.flush()

            val input = socket.inputStream
            val sizeBytes = readExact(input, 4)
            val size = ByteBuffer.wrap(sizeBytes).int
            val t1 = System.currentTimeMillis()

            if (size <= 0 || size > 20 * 1024 * 1024) {
                throw Exception("Invalid size: $size")
            }

            val jpegData = readExact(input, size)
            val t2 = System.currentTimeMillis()

            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                ?: throw Exception("Failed to decode JPEG")
            val t3 = System.currentTimeMillis()

            val waitMs = t1 - t0
            val recvMs = t2 - t1
            val decodeMs = t3 - t2
            val totalMs = t3 - t0
            val kb = size / 1024
            Log.i(TAG, "${bitmap.width}x${bitmap.height} ${kb}KB — wait:${waitMs}ms recv:${recvMs}ms decode:${decodeMs}ms total:${totalMs}ms")

            mainHandler.post { listener?.onScreenshotReceived(bitmap) }
        }
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
