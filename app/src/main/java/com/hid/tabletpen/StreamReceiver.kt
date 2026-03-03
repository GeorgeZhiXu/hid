package com.hid.tabletpen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to a raw H.264 Annex B TCP stream (from ffmpeg),
 * extracts SPS/PPS to configure MediaCodec, then feeds raw
 * chunks directly to the hardware decoder which renders to a Surface.
 */
class StreamReceiver {

    companion object {
        private const val TAG = "StreamReceiver"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_BUF_SIZE = 65536
        private const val MIME_TYPE = "video/avc"
    }

    interface Listener {
        fun onStreamConnected()
        fun onStreamDisconnected()
        fun onStreamError(message: String)
    }

    var listener: Listener? = null

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var decoder: MediaCodec? = null
    private var networkThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isStreaming: Boolean get() = running.get()

    fun start(host: String, port: Int, surface: Surface) {
        if (running.getAndSet(true)) return

        networkThread = Thread({
            try {
                connectAndDecode(host, port, surface)
            } catch (e: IOException) {
                Log.e(TAG, "Stream error", e)
                notifyError("Connection lost: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                notifyError("Error: ${e.message}")
            } finally {
                cleanup()
                running.set(false)
                mainHandler.post { listener?.onStreamDisconnected() }
            }
        }, "StreamReceiver").apply { start() }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        networkThread?.interrupt()
    }

    private fun connectAndDecode(host: String, port: Int, surface: Surface) {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        sock.tcpNoDelay = true
        sock.receiveBufferSize = 256 * 1024
        socket = sock
        Log.i(TAG, "TCP connected to $host:$port")
        mainHandler.post { listener?.onStreamConnected() }

        val input = sock.getInputStream()
        val readBuf = ByteArray(READ_BUF_SIZE)

        // Accumulate initial data until we find SPS and PPS
        val initBuf = java.io.ByteArrayOutputStream(READ_BUF_SIZE * 4)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var idrOffset = -1 // byte offset of first IDR NAL start code in initBuf

        Log.i(TAG, "Scanning for SPS/PPS...")
        while (running.get() && (sps == null || pps == null)) {
            val n = input.read(readBuf)
            if (n <= 0) {
                notifyError("Stream ended before SPS/PPS found")
                return
            }
            initBuf.write(readBuf, 0, n)
            val data = initBuf.toByteArray()
            val nalUnits = findNalUnits(data)
            for ((offset, len) in nalUnits) {
                val nalType = data[offset].toInt() and 0x1F
                Log.d(TAG, "Found NAL type=$nalType len=$len")
                when (nalType) {
                    7 -> sps = data.copyOfRange(offset, offset + len)
                    8 -> pps = data.copyOfRange(offset, offset + len)
                    5 -> if (idrOffset < 0) {
                        // Find start code before this NAL body
                        idrOffset = if (offset >= 4 && data[offset-4] == 0.toByte()) offset - 4 else offset - 3
                    }
                }
            }
        }

        if (sps == null || pps == null) {
            notifyError("Could not find SPS/PPS in stream")
            return
        }
        Log.i(TAG, "SPS(${sps.size} bytes) PPS(${pps.size} bytes) found, configuring decoder")

        // Configure MediaCodec
        val format = MediaFormat.createVideoFormat(MIME_TYPE, 1920, 1080).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        val codec = MediaCodec.createDecoderByType(MIME_TYPE)
        codec.configure(format, surface, null, 0)
        codec.start()
        decoder = codec
        Log.i(TAG, "MediaCodec started, idrOffset=$idrOffset")

        val bufferInfo = MediaCodec.BufferInfo()

        // Feed from the first IDR frame in the initial buffer (skip SPS/PPS/SEI before it)
        val initData = initBuf.toByteArray()
        if (idrOffset > 0 && idrOffset < initData.size) {
            feedData(codec, initData, idrOffset, initData.size - idrOffset, bufferInfo)
        }

        // Then stream from socket
        while (running.get()) {
            val n = input.read(readBuf)
            if (n <= 0) break
            feedData(codec, readBuf, 0, n, bufferInfo)
        }
    }

    private fun feedData(
        codec: MediaCodec, data: ByteArray, offset: Int, length: Int,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        var pos = offset
        val end = offset + length

        while (pos < end && running.get()) {
            val inputIndex = codec.dequeueInputBuffer(10000) // 10ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)!!
                val chunkSize = minOf(end - pos, inputBuffer.capacity())
                inputBuffer.clear()
                inputBuffer.put(data, pos, chunkSize)
                codec.queueInputBuffer(
                    inputIndex, 0, chunkSize,
                    System.nanoTime() / 1000, 0
                )
                pos += chunkSize
            }
            drainOutput(codec, bufferInfo)
        }
    }

    private fun drainOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
            } else {
                break
            }
        }
    }

    /**
     * Find NAL unit boundaries in Annex B data.
     * Returns list of (bodyOffset, bodyLength) pairs where bodyOffset
     * points to the first byte after the start code.
     */
    private fun findNalUnits(data: ByteArray): List<Pair<Int, Int>> {
        val units = mutableListOf<Int>() // start-code-end offsets (NAL body starts)
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 2 < data.size && data[i + 2] == 1.toByte()) {
                    units.add(i + 3)
                    i += 3
                    continue
                } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    units.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }

        // Convert to (offset, length) pairs
        return units.mapIndexed { idx, start ->
            val end = if (idx + 1 < units.size) {
                // Find the start code before the next NAL
                var e = units[idx + 1] - 3
                if (e > 0 && data[e - 1] == 0.toByte()) e-- // 4-byte start code
                e
            } else {
                data.size
            }
            Pair(start, end - start)
        }.filter { it.second > 0 }
    }

    private fun cleanup() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        decoder = null
        socket = null
    }

    private fun notifyError(message: String) {
        mainHandler.post { listener?.onStreamError(message) }
    }
}
