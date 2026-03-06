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
