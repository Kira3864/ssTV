package com.songshu.sstv

import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.songshu.sstv.ui.PlaybackSpeedSlider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "xhdpi")
class PlaybackSpeedSliderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { ThemeStore.setReducedMotion(app, true) }
    private fun slider() = PlaybackSpeedSlider(app).apply {
        measure(View.MeasureSpec.makeMeasureSpec(608, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(176, View.MeasureSpec.EXACTLY))
        layout(0, 0, 608, 176)
    }
    private fun touch(slider: PlaybackSpeedSlider, action: Int, x: Float) {
        val event = MotionEvent.obtain(0, 100, action, x, 88f, 0)
        slider.onTouchEvent(event); event.recycle()
    }
    @Test fun cancelRestoresCommittedSpeedAndIgnoresAnOrphanedRelease() {
        val slider = slider(); slider.setSpeed(.75f)
        val applied = mutableListOf<Float>(); slider.onSpeedSelected = { applied.add(it) }
        touch(slider, MotionEvent.ACTION_DOWN, 304f)
        touch(slider, MotionEvent.ACTION_MOVE, 10f)
        assertEquals(.75f, slider.selectedSpeed, .001f)
        touch(slider, MotionEvent.ACTION_CANCEL, 10f)
        touch(slider, MotionEvent.ACTION_UP, 10f)
        assertEquals(.75f, slider.selectedSpeed, .001f)
        assertTrue(applied.isEmpty())
    }
    @Test fun draggingClampsBothEndsAndAccessibilityCanSelectAdjacentRates() {
        val slider = slider()
        val applied = mutableListOf<Float>(); slider.onSpeedSelected = { applied.add(it) }
        touch(slider, MotionEvent.ACTION_DOWN, 304f)
        touch(slider, MotionEvent.ACTION_MOVE, -3000f)
        touch(slider, MotionEvent.ACTION_UP, -3000f)
        assertEquals(5f, slider.selectedSpeed, .001f)
        assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(4f, slider.selectedSpeed, .001f)
        touch(slider, MotionEvent.ACTION_DOWN, 304f)
        touch(slider, MotionEvent.ACTION_MOVE, 3000f)
        touch(slider, MotionEvent.ACTION_UP, 3000f)
        assertEquals(.5f, slider.selectedSpeed, .001f)
        slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(.5f, slider.selectedSpeed, .001f)
        assertEquals(slider.selectedSpeed, applied.last(), .001f)
    }
}
