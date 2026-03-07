package com.hid.tabletpen

import android.os.SystemClock
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Instrumented test that injects real multi-touch events to verify
 * two-finger tap gesture handling doesn't crash.
 *
 * Run with: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hid.tabletpen.TwoFingerTapTest
 */
class TwoFingerTapTest {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage("com.hid.tabletpen")
        assertNotNull("TabletPen app not installed", intent)
        context.startActivity(intent)
        device.waitForIdle(3000)
        Thread.sleep(1000)
    }

    @Test
    fun twoFingerTap_singleTap_nocrash() {
        injectTwoFingerTap(600f, 500f, 700f, 500f)
        Thread.sleep(300)
        // If we get here without exception, the gesture was handled
    }

    @Test
    fun twoFingerTap_repeatedTaps_nocrash() {
        repeat(10) {
            injectTwoFingerTap(600f, 500f, 700f, 500f)
            Thread.sleep(200)
        }
    }

    @Test
    fun twoFingerTap_varyingPositions_nocrash() {
        val positions = listOf(
            Pair(400f, 400f), Pair(800f, 400f),
            Pair(400f, 700f), Pair(800f, 700f),
            Pair(600f, 550f)
        )
        for ((x, y) in positions) {
            injectTwoFingerTap(x, y, x + 80f, y)
            Thread.sleep(250)
        }
    }

    @Test
    fun twoFingerTap_withSlightMovement_nocrash() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()

        val pp0 = PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }

        val pc0 = PointerCoords().apply { x = 600f; y = 500f; pressure = 1f; size = 1f }
        val pc1 = PointerCoords().apply { x = 700f; y = 500f; pressure = 1f; size = 1f }

        inject(uiAutomation, downTime, downTime, MotionEvent.ACTION_DOWN, 1, arrayOf(pp0), arrayOf(pc0))
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, arrayOf(pp0, pp1), arrayOf(pc0, pc1))

        // Slight jitter
        Thread.sleep(10)
        val pc0m = PointerCoords().apply { x = 602f; y = 501f; pressure = 1f; size = 1f }
        val pc1m = PointerCoords().apply { x = 698f; y = 502f; pressure = 1f; size = 1f }
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, arrayOf(pp0, pp1), arrayOf(pc0m, pc1m))

        Thread.sleep(30)

        inject(uiAutomation, downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, arrayOf(pp0, pp1), arrayOf(pc0m, pc1m))
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, arrayOf(pp0), arrayOf(pc0m))

        Thread.sleep(300)
    }

    private fun injectTwoFingerTap(x1: Float, y1: Float, x2: Float, y2: Float) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()

        val pp0 = PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pp1 = PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val pc0 = PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f }
        val pc1 = PointerCoords().apply { x = x2; y = y2; pressure = 1f; size = 1f }

        inject(uiAutomation, downTime, downTime, MotionEvent.ACTION_DOWN, 1, arrayOf(pp0), arrayOf(pc0))
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, arrayOf(pp0, pp1), arrayOf(pc0, pc1))
        Thread.sleep(40)
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, arrayOf(pp0, pp1), arrayOf(pc0, pc1))
        inject(uiAutomation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, arrayOf(pp0), arrayOf(pc0))
    }

    private fun inject(
        uiAutomation: android.app.UiAutomation,
        downTime: Long, eventTime: Long, action: Int,
        pointerCount: Int, props: Array<PointerProperties>, coords: Array<PointerCoords>
    ) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, pointerCount, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        uiAutomation.injectInputEvent(ev, true)
        ev.recycle()
    }
}
