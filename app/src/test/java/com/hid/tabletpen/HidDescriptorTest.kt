package com.hid.tabletpen

import org.junit.Assert.*
import org.junit.Test

class HidDescriptorTest {

    // ---- buildReport (digitizer) ----

    @Test
    fun `buildReport tip down sets bit 0`() {
        val report = HidDescriptor.buildReport(tipDown = true, barrel = false, inRange = false, 0, 0, 0)
        assertEquals(0x01, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport barrel sets bit 1`() {
        val report = HidDescriptor.buildReport(tipDown = false, barrel = true, inRange = false, 0, 0, 0)
        assertEquals(0x02, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport inRange sets bit 2`() {
        val report = HidDescriptor.buildReport(tipDown = false, barrel = false, inRange = true, 0, 0, 0)
        assertEquals(0x04, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport all buttons combines bits`() {
        val report = HidDescriptor.buildReport(tipDown = true, barrel = true, inRange = true, 0, 0, 0)
        assertEquals(0x07, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildReport X encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 0x1234, y = 0, pressure = 0)
        assertEquals(0x34, report[1].toInt() and 0xFF) // low byte
        assertEquals(0x12, report[2].toInt() and 0xFF) // high byte
    }

    @Test
    fun `buildReport Y encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 0, y = 0x5678, pressure = 0)
        assertEquals(0x78, report[3].toInt() and 0xFF) // low byte
        assertEquals(0x56, report[4].toInt() and 0xFF) // high byte
    }

    @Test
    fun `buildReport pressure encoded as little-endian uint16`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, pressure = 0x0ABC)
        assertEquals(0xBC.toByte(), report[5])
        assertEquals(0x0A, report[6].toInt() and 0xFF)
    }

    @Test
    fun `buildReport clamps X to max`() {
        val report = HidDescriptor.buildReport(false, false, false, x = 50000, y = 0, pressure = 0)
        val x = (report[1].toInt() and 0xFF) or ((report[2].toInt() and 0xFF) shl 8)
        assertEquals(HidDescriptor.X_MAX, x)
    }

    @Test
    fun `buildReport clamps negative X to 0`() {
        val report = HidDescriptor.buildReport(false, false, false, x = -100, y = 0, pressure = 0)
        val x = (report[1].toInt() and 0xFF) or ((report[2].toInt() and 0xFF) shl 8)
        assertEquals(0, x)
    }

    @Test
    fun `buildReport clamps pressure to max`() {
        val report = HidDescriptor.buildReport(false, false, false, 0, 0, pressure = 9999)
        val p = (report[5].toInt() and 0xFF) or ((report[6].toInt() and 0xFF) shl 8)
        assertEquals(HidDescriptor.PRESSURE_MAX, p)
    }

    @Test
    fun `buildReport returns 7 bytes`() {
        val report = HidDescriptor.buildReport(true, true, true, 32767, 32767, 4095)
        assertEquals(HidDescriptor.DIGITIZER_REPORT_SIZE, report.size)
    }

    // ---- buildMouseReport ----

    @Test
    fun `buildMouseReport left button sets bit 0`() {
        val report = HidDescriptor.buildMouseReport(left = true, right = false, middle = false, 0, 0)
        assertEquals(0x01, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildMouseReport right button sets bit 1`() {
        val report = HidDescriptor.buildMouseReport(left = false, right = true, middle = false, 0, 0)
        assertEquals(0x02, report[0].toInt() and 0xFF)
    }

    @Test
    fun `buildMouseReport clamps dx to 127`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, dx = 500, dy = 0)
        assertEquals(127, report[1].toInt())
    }

    @Test
    fun `buildMouseReport clamps dx to negative 127`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, dx = -500, dy = 0)
        assertEquals(-127, report[1].toInt())
    }

    @Test
    fun `buildMouseReport scroll value`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, 0, 0, scroll = 42)
        assertEquals(42, report[3].toInt())
    }

    @Test
    fun `buildMouseReport returns 4 bytes`() {
        val report = HidDescriptor.buildMouseReport(false, false, false, 0, 0)
        assertEquals(HidDescriptor.MOUSE_REPORT_SIZE, report.size)
    }

    // ---- buildKeyboardReport ----

    @Test
    fun `buildKeyboardReport sets modifier byte`() {
        val report = HidDescriptor.buildKeyboardReport(HidDescriptor.MOD_LEFT_CTRL)
        assertEquals(HidDescriptor.MOD_LEFT_CTRL.toByte(), report[0])
    }

    @Test
    fun `buildKeyboardReport remaining bytes are zero`() {
        val report = HidDescriptor.buildKeyboardReport(0xFF)
        for (i in 1 until report.size) {
            assertEquals("byte $i should be 0", 0, report[i].toInt())
        }
    }

    @Test
    fun `buildKeyboardReport returns 8 bytes`() {
        val report = HidDescriptor.buildKeyboardReport(0)
        assertEquals(HidDescriptor.KEYBOARD_REPORT_SIZE, report.size)
    }

    // ---- DESCRIPTOR ----

    @Test
    fun `descriptor starts with digitizer usage page`() {
        assertEquals(0x05, HidDescriptor.DESCRIPTOR[0].toInt() and 0xFF)
        assertEquals(0x0D, HidDescriptor.DESCRIPTOR[1].toInt() and 0xFF)
    }

    @Test
    fun `descriptor is non-empty`() {
        assertTrue(HidDescriptor.DESCRIPTOR.size > 50)
    }
}
