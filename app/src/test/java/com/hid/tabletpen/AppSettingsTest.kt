package com.hid.tabletpen

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `AspectRatio 16-10 ratio is 1_6`() {
        assertEquals(1.6f, AspectRatio.RATIO_16_10.ratio, 0.001f)
    }

    @Test
    fun `AspectRatio 16-9 ratio is 1_778`() {
        assertEquals(16f / 9f, AspectRatio.RATIO_16_9.ratio, 0.001f)
    }

    @Test
    fun `AspectRatio 3-2 ratio is 1_5`() {
        assertEquals(1.5f, AspectRatio.RATIO_3_2.ratio, 0.001f)
    }

    @Test
    fun `AspectRatio toString`() {
        assertEquals("16:10", AspectRatio.RATIO_16_10.toString())
        assertEquals("16:9", AspectRatio.RATIO_16_9.toString())
    }

    @Test
    fun `AspectRatio presets count`() {
        assertEquals(3, AspectRatio.PRESETS.size)
    }

    @Test
    fun `CursorStyle ordinals`() {
        assertEquals(0, CursorStyle.NONE.ordinal)
        assertEquals(1, CursorStyle.CROSSHAIR.ordinal)
        assertEquals(2, CursorStyle.DOT.ordinal)
        assertEquals(3, CursorStyle.CIRCLE.ordinal)
    }

    @Test
    fun `CursorStyle labels count matches entries`() {
        assertEquals(CursorStyle.entries.size, CursorStyle.LABELS.size)
    }

    @Test
    fun `InputMode has two values`() {
        assertEquals(2, InputMode.entries.size)
        assertEquals(0, InputMode.DIGITIZER.ordinal)
        assertEquals(1, InputMode.MOUSE.ordinal)
    }

    @Test
    fun `StrokeColor ordinals`() {
        assertEquals(0, StrokeColor.AUTO.ordinal)
        assertEquals(1, StrokeColor.WHITE.ordinal)
        assertEquals(2, StrokeColor.BLACK.ordinal)
        assertEquals(3, StrokeColor.RED.ordinal)
        assertEquals(4, StrokeColor.BLUE.ordinal)
    }

    @Test
    fun `StrokeColor labels count matches entries`() {
        assertEquals(StrokeColor.entries.size, StrokeColor.LABELS.size)
    }
}
