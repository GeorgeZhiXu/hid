package com.hid.tabletpen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Requests screenshots from the Mac over HTTP.
 * Uses ADB reverse tunnel: tablet localhost:9877 → Mac localhost:9877
 * No WiFi needed — works over USB or ADB wireless debug.
 */
class BluetoothScreenshot {

    companion object {
        private const val TAG = "Screenshot"
        private const val SCREENSHOT_URL = "http://127.0.0.1:9877/"
        private const val TIMEOUT_MS = 10000
    }

    interface Listener {
        fun onScreenshotReceived(bitmap: Bitmap)
        fun onScreenshotError(message: String)
    }

    var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun requestScreenshot() {
        Thread({
            try {
                doRequest()
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                mainHandler.post { listener?.onScreenshotError(e.message ?: "Unknown error") }
            }
        }, "Screenshot").start()
    }

    private fun doRequest() {
        Log.i(TAG, "Requesting screenshot...")
        val url = URL(SCREENSHOT_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS

        try {
            conn.connect()
            if (conn.responseCode != 200) {
                throw Exception("HTTP ${conn.responseCode}")
            }

            val data = conn.inputStream.readBytes()
            Log.i(TAG, "Received ${data.size} bytes, decoding...")

            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw Exception("Failed to decode JPEG")

            Log.i(TAG, "Screenshot: ${bitmap.width}x${bitmap.height}")
            mainHandler.post { listener?.onScreenshotReceived(bitmap) }
        } finally {
            conn.disconnect()
        }
    }
}
