package com.hid.tabletpen

import org.junit.Assert.*
import org.junit.Test

class PenMathTest {

    // ---- calculatePressure ----

    @Test
    fun `pressure is 0 when tip not down`() {
        assertEquals(0, PenMath.calculatePressure(false, 0.5f, 0.8f, 0.5f, 4095))
    }

    @Test
    fun `pressure at max input with zero floor`() {
        val p = PenMath.calculatePressure(true, 1.0f, 0.0f, 1.0f, 4095)
        assertEquals(4095, p)
    }

    @Test
    fun `pressure floor guarantees minimum`() {
        val p = PenMath.calculatePressure(true, 0.0f, 0.8f, 0.5f, 4095)
        assertEquals((0.8f * 4095).toInt(), p)
    }

    @Test
    fun `pressure at full input with floor`() {
        val p = PenMath.calculatePressure(true, 1.0f, 0.8f, 0.5f, 4095)
        assertEquals(4095, p)
    }

    @Test
    fun `pressure square root curve at half input`() {
        val p = PenMath.calculatePressure(true, 0.5f, 0.0f, 0.5f, 4095)
        val expected = (Math.sqrt(0.5) * 4095).toInt()
        assertEquals(expected, p)
    }

    @Test
    fun `pressure with zero floor and linear curve`() {
        val p = PenMath.calculatePressure(true, 0.5f, 0.0f, 1.0f, 4095)
        assertEquals((0.5f * 4095).toInt(), p)
    }

    @Test
    fun `pressure max is 1000`() {
        val p = PenMath.calculatePressure(true, 1.0f, 0.0f, 1.0f, 1000)
        assertEquals(1000, p)
    }

    // ---- chunkMouseDeltas ----

    @Test
    fun `small delta is single chunk`() {
        val chunks = PenMath.chunkMouseDeltas(50, -30)
        assertEquals(1, chunks.size)
        assertEquals(Pair(50, -30), chunks[0])
    }

    @Test
    fun `zero delta is single chunk`() {
        val chunks = PenMath.chunkMouseDeltas(0, 0)
        assertEquals(1, chunks.size)
        assertEquals(Pair(0, 0), chunks[0])
    }

    @Test
    fun `boundary value 127`() {
        val chunks = PenMath.chunkMouseDeltas(127, -127)
        assertEquals(1, chunks.size)
        assertEquals(Pair(127, -127), chunks[0])
    }

    @Test
    fun `large delta splits into multiple chunks`() {
        val chunks = PenMath.chunkMouseDeltas(300, 0)
        assertTrue(chunks.size > 1)
        val totalDx = chunks.sumOf { it.first }
        assertEquals(300, totalDx)
        chunks.forEach { assertTrue(it.first in -127..127) }
    }

    @Test
    fun `negative large delta splits correctly`() {
        val chunks = PenMath.chunkMouseDeltas(-500, -200)
        val totalDx = chunks.sumOf { it.first }
        val totalDy = chunks.sumOf { it.second }
        assertEquals(-500, totalDx)
        assertEquals(-200, totalDy)
    }

    @Test
    fun `very large delta chunks all within bounds`() {
        val chunks = PenMath.chunkMouseDeltas(1000, -1000)
        for ((dx, dy) in chunks) {
            assertTrue("dx=$dx out of range", dx in -127..127)
            assertTrue("dy=$dy out of range", dy in -127..127)
        }
        assertEquals(1000, chunks.sumOf { it.first })
        assertEquals(-1000, chunks.sumOf { it.second })
    }

    // ---- mapThroughFocus ----
    // Note: RectF doesn't work in JVM unit tests, so we test the null case only.
    // RectF-dependent tests belong in androidTest (instrumented).

    @Test
    fun `null focus returns input unchanged`() {
        val (x, y) = PenMath.mapThroughFocus(0.5f, 0.3f, null)
        assertEquals(0.5f, x, 0.001f)
        assertEquals(0.3f, y, 0.001f)
    }

    // ---- computeActiveRect ----
    // Note: Returns RectF which is stubbed in JVM tests.
    // Tested via instrumented tests or manual verification.

    // ---- parseWifiInfo ----

    @Test
    fun `valid wifi info`() {
        val result = PenMath.parseWifiInfo("wifi:192.168.1.100:9877")
        assertNotNull(result)
        assertEquals("192.168.1.100", result!!.first)
        assertEquals(9877, result.second)
    }

    @Test
    fun `localhost with port`() {
        val result = PenMath.parseWifiInfo("wifi:localhost:8080")
        assertNotNull(result)
        assertEquals("localhost", result!!.first)
        assertEquals(8080, result.second)
    }

    @Test
    fun `invalid prefix`() {
        assertNull(PenMath.parseWifiInfo("notawifi:host:1234"))
    }

    @Test
    fun `missing port`() {
        assertNull(PenMath.parseWifiInfo("wifi:hostonly"))
    }

    @Test
    fun `non-numeric port`() {
        assertNull(PenMath.parseWifiInfo("wifi:host:abc"))
    }

    @Test
    fun `zero port`() {
        assertNull(PenMath.parseWifiInfo("wifi:host:0"))
    }

    @Test
    fun `negative port`() {
        assertNull(PenMath.parseWifiInfo("wifi:host:-1"))
    }

    @Test
    fun `empty string`() {
        assertNull(PenMath.parseWifiInfo(""))
    }

    @Test
    fun `wifi prefix only`() {
        assertNull(PenMath.parseWifiInfo("wifi:"))
    }

    // ---- computeAdaptiveQuality ----

    @Test
    fun `fast WiFi gets high quality`() {
        // 500KB in 200ms = 2.5MB/s
        val (q, max) = PenMath.computeAdaptiveQuality(200, 500_000)
        assertEquals(60, q)
        assertEquals(1920, max)
    }

    @Test
    fun `medium WiFi gets medium quality`() {
        // 100KB in 500ms = 200KB/s
        val (q, max) = PenMath.computeAdaptiveQuality(500, 100_000)
        assertEquals(40, q)
        assertEquals(1280, max)
    }

    @Test
    fun `slow BT gets low quality`() {
        // 60KB in 4000ms = 15KB/s
        val (q, max) = PenMath.computeAdaptiveQuality(4000, 60_000)
        assertEquals(25, q)
        assertEquals(960, max)
    }

    @Test
    fun `no data returns default`() {
        val (q, max) = PenMath.computeAdaptiveQuality(0, 0)
        assertEquals(35, q)
        assertEquals(1280, max)
    }

    @Test
    fun `negative values return default`() {
        val (q, max) = PenMath.computeAdaptiveQuality(-1, -1)
        assertEquals(35, q)
        assertEquals(1280, max)
    }
}

class StrokeColorTest {

    @Test
    fun `null bitmap returns white`() {
        assertEquals(android.graphics.Color.WHITE, PenMath.detectContrastColor(null))
    }
}

class RadialMenuMathTest {

    // Radial menu: angle-to-segment mapping
    // Segments start from top (0°=up), clockwise
    // For 8 segments: each is 45° wide

    private fun angleToSegment(dx: Float, dy: Float, segCount: Int): Int {
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val normalized = ((angle + 90 + 360) % 360)  // 0 = top
        return ((normalized / (360f / segCount)).toInt()) % segCount
    }

    @Test
    fun `top direction maps to segment 0`() {
        assertEquals(0, angleToSegment(0f, -100f, 8))  // straight up
    }

    @Test
    fun `right direction maps to segment 2`() {
        assertEquals(2, angleToSegment(100f, 0f, 8))  // straight right
    }

    @Test
    fun `bottom direction maps to segment 4`() {
        assertEquals(4, angleToSegment(0f, 100f, 8))  // straight down
    }

    @Test
    fun `left direction maps to segment 6`() {
        assertEquals(6, angleToSegment(-100f, 0f, 8))  // straight left
    }

    @Test
    fun `top-right diagonal maps to segment 1`() {
        assertEquals(1, angleToSegment(100f, -100f, 8))
    }

    @Test
    fun `bottom-left diagonal maps to segment 5`() {
        assertEquals(5, angleToSegment(-100f, 100f, 8))
    }

    @Test
    fun `4 segments maps correctly`() {
        assertEquals(0, angleToSegment(0f, -100f, 4))   // up = 0
        assertEquals(1, angleToSegment(100f, 0f, 4))     // right = 1
        assertEquals(2, angleToSegment(0f, 100f, 4))     // down = 2
        assertEquals(3, angleToSegment(-100f, 0f, 4))    // left = 3
    }

    @Test
    fun `6 segments maps correctly`() {
        assertEquals(0, angleToSegment(0f, -100f, 6))   // up
        assertEquals(3, angleToSegment(0f, 100f, 6))     // down
    }
}

class DeltaFrameTest {

    @Test
    fun `delta tile data class holds values`() {
        val tile = BluetoothScreenshot.DeltaTile(10, 20, 64, 64, byteArrayOf(1, 2, 3))
        assertEquals(10, tile.x)
        assertEquals(20, tile.y)
        assertEquals(64, tile.w)
        assertEquals(64, tile.h)
        assertEquals(3, tile.jpeg.size)
    }

    @Test
    fun `delta payload parsing with ByteBuffer`() {
        // Simulate a delta payload: 2 tiles
        val buf = java.nio.ByteBuffer.allocate(100)
        buf.putShort(2)  // tile count

        // Tile 1
        buf.putShort(0); buf.putShort(0); buf.putShort(64); buf.putShort(64)
        val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())  // fake JPEG header
        buf.putInt(jpeg1.size)
        buf.put(jpeg1)

        // Tile 2
        buf.putShort(64); buf.putShort(0); buf.putShort(64); buf.putShort(64)
        val jpeg2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        buf.putInt(jpeg2.size)
        buf.put(jpeg2)

        // Parse
        buf.flip()
        val tileCount = buf.short.toInt() and 0xFFFF
        assertEquals(2, tileCount)

        val tiles = mutableListOf<BluetoothScreenshot.DeltaTile>()
        for (i in 0 until tileCount) {
            val tx = buf.short.toInt() and 0xFFFF
            val ty = buf.short.toInt() and 0xFFFF
            val tw = buf.short.toInt() and 0xFFFF
            val th = buf.short.toInt() and 0xFFFF
            val jpegSize = buf.int
            val jpeg = ByteArray(jpegSize)
            buf.get(jpeg)
            tiles.add(BluetoothScreenshot.DeltaTile(tx, ty, tw, th, jpeg))
        }

        assertEquals(2, tiles.size)
        assertEquals(0, tiles[0].x)
        assertEquals(64, tiles[1].x)
        assertEquals(3, tiles[0].jpeg.size)
        assertEquals(2, tiles[1].jpeg.size)
    }

    @Test
    fun `empty delta payload`() {
        val buf = java.nio.ByteBuffer.allocate(2)
        buf.putShort(0)  // 0 tiles
        buf.flip()
        val tileCount = buf.short.toInt() and 0xFFFF
        assertEquals(0, tileCount)
    }
}
