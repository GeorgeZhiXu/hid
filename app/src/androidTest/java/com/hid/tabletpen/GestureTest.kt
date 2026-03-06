package com.hid.tabletpen

import android.graphics.Point
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Instrumented gesture tests for DrawPadView.
 * Runs on the actual device, injecting touch events via UiAutomator.
 * Verifies the app doesn't crash under various gesture patterns.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
 */
class GestureTest {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Launch the app
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.hid.tabletpen")
        assertNotNull("TabletPen app not installed", intent)
        context.startActivity(intent)
        device.waitForIdle(3000)
    }

    @Test
    fun singleFingerDrag_nocrash() {
        // Horizontal swipe across draw pad
        device.swipe(500, 600, 900, 600, 20)
        Thread.sleep(300)
        // Vertical swipe
        device.swipe(700, 400, 700, 800, 20)
        Thread.sleep(300)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun singleFingerTap_nocrash() {
        device.click(700, 600)
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun twoFingerScroll_nocrash() {
        // Two-finger vertical scroll using multi-pointer gesture
        // UiDevice doesn't have performMultiPointerGesture, so we use
        // swipe with two sequential single-finger swipes to approximate
        val points1 = arrayOf(Point(600, 600), Point(600, 400))
        val points2 = arrayOf(Point(700, 600), Point(700, 400))
        device.swipe(points1, 10)
        device.swipe(points2, 10)
        Thread.sleep(300)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun rapidTaps_nocrash() {
        for (i in 1..20) {
            device.click(500 + (i * 15), 500)
            Thread.sleep(30)
        }
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun rapidSwipes_nocrash() {
        for (i in 1..10) {
            device.swipe(400, 500, 900, 500, 5)
            Thread.sleep(50)
        }
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun mixedGestures_nocrash() {
        // Tap → drag → tap → drag → tap
        device.click(600, 500)
        Thread.sleep(100)
        device.swipe(500, 600, 900, 600, 10)
        Thread.sleep(100)
        device.click(700, 500)
        Thread.sleep(100)
        device.swipe(700, 400, 700, 700, 10)
        Thread.sleep(100)
        device.click(800, 500)
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun longPress_nocrash() {
        // Long press (>400ms) should trigger right-click in mouse mode
        device.swipe(700, 500, 700, 500, 100) // 0-distance swipe = hold
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun longPressForRadialMenu_nocrash() {
        // Long press (>500ms) should trigger radial menu
        // Hold finger still on draw pad area
        device.swipe(700, 500, 700, 500, 200)  // 200 steps = ~2s hold
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun radialMenuDragAndRelease_nocrash() {
        // Long press to open radial menu, drag to a segment, release
        device.swipe(700, 500, 700, 500, 150)  // hold to trigger menu
        Thread.sleep(100)
        device.swipe(700, 500, 800, 400, 10)   // drag to a segment
        Thread.sleep(500)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun shortcutButtonTap_nocrash() {
        // Tap on the shortcut toolbar area (top of screen)
        device.click(200, 120)  // first shortcut button area
        Thread.sleep(200)
        device.click(400, 120)  // second shortcut button area
        Thread.sleep(200)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }

    @Test
    fun edgeOfScreen_nocrash() {
        // Tap near edges — boundary conditions
        device.click(10, 10)      // top-left corner
        Thread.sleep(100)
        device.click(1900, 1000)  // bottom-right area
        Thread.sleep(100)
        device.click(0, 500)      // left edge
        Thread.sleep(100)
        assertEquals("com.hid.tabletpen", device.currentPackageName)
    }
}
