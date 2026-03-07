package com.hid.tabletpen

import android.os.SystemClock
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instrumented tests with functional assertions for trackpad gestures.
 * Dispatches MotionEvents directly to DrawPadView.onTouchEvent to test
 * gesture logic in isolation from the system input pipeline.
 *
 * Run with: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hid.tabletpen.TwoFingerTapTest
 */
class TwoFingerTapTest {

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val mouseEvents = CopyOnWriteArrayList<DrawPadView.MouseEvent>()
    private lateinit var drawPad: DrawPadView

    @Before
    fun setup() {
        mouseEvents.clear()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(1500)
        scenario.onActivity { activity ->
            drawPad = activity.findViewById(R.id.draw_pad)
            drawPad.testMouseEvents = mouseEvents
        }
        Thread.sleep(300)
    }

    @After
    fun teardown() {
        try { scenario.onActivity { drawPad.testMouseEvents = null } } catch (_: Exception) {}
        scenario.close()
    }

    private fun dispatch(ev: MotionEvent) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            drawPad.dispatchTouchEvent(ev)
        }
        ev.recycle()
    }

    // ---- Two-finger tap → right click ----

    @Test
    fun twoFingerTap_triggersRightClick() {
        mouseEvents.clear()
        sendTwoFingerTap(400f, 300f, 500f, 300f)
        Thread.sleep(300)

        val rightClicks = mouseEvents.filter { it.rightButton }
        assertTrue("Expected right-click, got: ${mouseEvents.summarize()}", rightClicks.isNotEmpty())
    }

    @Test
    fun twoFingerTap_followedByRelease() {
        mouseEvents.clear()
        sendTwoFingerTap(400f, 300f, 500f, 300f)
        Thread.sleep(300)

        assertTrue("Expected right-click press", mouseEvents.any { it.rightButton })
        assertTrue("Expected release after", mouseEvents.any { !it.rightButton && !it.leftButton })
    }

    @Test
    fun twoFingerTap_repeated_allTriggerRightClick() {
        var ok = 0
        repeat(5) {
            mouseEvents.clear()
            sendTwoFingerTap(400f, 300f, 500f, 300f)
            Thread.sleep(300)
            if (mouseEvents.any { it.rightButton }) ok++
        }
        assertTrue("Expected >=4/5 right-clicks, got $ok", ok >= 4)
    }

    // ---- Single finger tap → left click ----

    @Test
    fun singleFingerTap_triggersLeftClick() {
        mouseEvents.clear()
        sendSingleFingerTap(400f, 300f)
        Thread.sleep(300)

        assertTrue("Expected left-click, got: ${mouseEvents.summarize()}", mouseEvents.any { it.leftButton })
    }

    @Test
    fun singleFingerTap_noRightClick() {
        mouseEvents.clear()
        sendSingleFingerTap(400f, 300f)
        Thread.sleep(300)

        assertFalse("Single tap should not right-click", mouseEvents.any { it.rightButton })
    }

    // ---- Single finger drag → mouse move ----

    @Test
    fun singleFingerDrag_generatesMouseMove() {
        mouseEvents.clear()
        sendSingleFingerDrag(300f, 300f, 500f, 300f, steps = 10)
        Thread.sleep(300)

        val moves = mouseEvents.filter { it.dx != 0f || it.dy != 0f }
        assertTrue("Expected mouse moves, got ${moves.size}", moves.size >= 3)
        assertTrue("Expected positive dx", moves.sumOf { it.dx.toDouble() } > 0)
    }

    @Test
    fun singleFingerDrag_noButtons() {
        mouseEvents.clear()
        sendSingleFingerDrag(300f, 300f, 500f, 300f, steps = 10)
        Thread.sleep(300)

        val withBtn = mouseEvents.filter { (it.dx != 0f || it.dy != 0f) && (it.leftButton || it.rightButton) }
        assertTrue("Drag should not press buttons", withBtn.isEmpty())
    }

    // ---- Two-finger scroll ----

    @Test
    fun twoFingerDrag_generatesScroll() {
        mouseEvents.clear()
        sendTwoFingerDrag(350f, 350f, 450f, 350f, 0f, -80f, steps = 15)
        Thread.sleep(300)

        val scrolls = mouseEvents.filter { it.scroll != 0f }
        assertTrue("Expected scroll events, got: ${mouseEvents.summarize()}", scrolls.isNotEmpty())
    }

    // ---- Jitter tolerance ----

    @Test
    fun twoFingerTap_smallJitter_stillRightClicks() {
        mouseEvents.clear()
        sendTwoFingerTapWithJitter(400f, 300f, 500f, 300f, 3f)
        Thread.sleep(300)

        assertTrue("Small jitter should still right-click", mouseEvents.any { it.rightButton })
    }

    @Test
    fun twoFingerDrag_doesNotRightClick() {
        mouseEvents.clear()
        sendTwoFingerDrag(350f, 350f, 450f, 350f, 0f, -60f, steps = 10)
        Thread.sleep(300)

        val rightTaps = mouseEvents.filter { it.rightButton && it.dx == 0f && it.dy == 0f }
        assertTrue("Large drag should not right-click", rightTaps.isEmpty())
    }

    // ---- Gesture senders (dispatch directly to DrawPadView) ----

    private fun sendTwoFingerTap(x1: Float, y1: Float, x2: Float, y2: Float) {
        val dt = SystemClock.uptimeMillis()
        dispatch(make(dt, dt, MotionEvent.ACTION_DOWN, arrayOf(pp(0)), arrayOf(pc(x1, y1))))
        dispatch(make(dt, now(), ptrDown(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1, y1), pc(x2, y2))))
        Thread.sleep(40)
        dispatch(make(dt, now(), ptrUp(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1, y1), pc(x2, y2))))
        dispatch(make(dt, now(), MotionEvent.ACTION_UP, arrayOf(pp(0)), arrayOf(pc(x1, y1))))
    }

    private fun sendTwoFingerTapWithJitter(x1: Float, y1: Float, x2: Float, y2: Float, j: Float) {
        val dt = SystemClock.uptimeMillis()
        dispatch(make(dt, dt, MotionEvent.ACTION_DOWN, arrayOf(pp(0)), arrayOf(pc(x1, y1))))
        dispatch(make(dt, now(), ptrDown(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1, y1), pc(x2, y2))))
        Thread.sleep(10)
        dispatch(make(dt, now(), MotionEvent.ACTION_MOVE, arrayOf(pp(0), pp(1)), arrayOf(pc(x1 + j, y1 - j), pc(x2 - j, y2 + j))))
        Thread.sleep(30)
        dispatch(make(dt, now(), ptrUp(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1 + j, y1 - j), pc(x2 - j, y2 + j))))
        dispatch(make(dt, now(), MotionEvent.ACTION_UP, arrayOf(pp(0)), arrayOf(pc(x1 + j, y1 - j))))
    }

    private fun sendSingleFingerTap(x: Float, y: Float) {
        val dt = SystemClock.uptimeMillis()
        dispatch(make(dt, dt, MotionEvent.ACTION_DOWN, arrayOf(pp(0)), arrayOf(pc(x, y))))
        Thread.sleep(30)
        dispatch(make(dt, now(), MotionEvent.ACTION_UP, arrayOf(pp(0)), arrayOf(pc(x, y))))
    }

    private fun sendSingleFingerDrag(x1: Float, y1: Float, x2: Float, y2: Float, steps: Int) {
        val dt = SystemClock.uptimeMillis()
        dispatch(make(dt, dt, MotionEvent.ACTION_DOWN, arrayOf(pp(0)), arrayOf(pc(x1, y1))))
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            Thread.sleep(15)
            dispatch(make(dt, now(), MotionEvent.ACTION_MOVE, arrayOf(pp(0)), arrayOf(pc(x1 + (x2 - x1) * f, y1 + (y2 - y1) * f))))
        }
        Thread.sleep(10)
        dispatch(make(dt, now(), MotionEvent.ACTION_UP, arrayOf(pp(0)), arrayOf(pc(x2, y2))))
    }

    private fun sendTwoFingerDrag(x1: Float, y1: Float, x2: Float, y2: Float, dx: Float, dy: Float, steps: Int) {
        val dt = SystemClock.uptimeMillis()
        dispatch(make(dt, dt, MotionEvent.ACTION_DOWN, arrayOf(pp(0)), arrayOf(pc(x1, y1))))
        dispatch(make(dt, now(), ptrDown(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1, y1), pc(x2, y2))))
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            Thread.sleep(15)
            dispatch(make(dt, now(), MotionEvent.ACTION_MOVE, arrayOf(pp(0), pp(1)),
                arrayOf(pc(x1 + dx * f, y1 + dy * f), pc(x2 + dx * f, y2 + dy * f))))
        }
        Thread.sleep(10)
        dispatch(make(dt, now(), ptrUp(1), arrayOf(pp(0), pp(1)), arrayOf(pc(x1 + dx, y1 + dy), pc(x2 + dx, y2 + dy))))
        dispatch(make(dt, now(), MotionEvent.ACTION_UP, arrayOf(pp(0)), arrayOf(pc(x1 + dx, y1 + dy))))
    }

    // ---- Primitives ----

    private fun now() = SystemClock.uptimeMillis()
    private fun pp(id: Int) = PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
    private fun pc(x: Float, y: Float) = PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
    private fun ptrDown(i: Int) = MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun ptrUp(i: Int) = MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun make(downTime: Long, eventTime: Long, action: Int, props: Array<PointerProperties>, coords: Array<PointerCoords>): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, props.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)

    private fun List<DrawPadView.MouseEvent>.summarize() =
        take(10).map { "L=${it.leftButton} R=${it.rightButton} dx=${"%.0f".format(it.dx)} dy=${"%.0f".format(it.dy)} s=${"%.0f".format(it.scroll)}" }
}
