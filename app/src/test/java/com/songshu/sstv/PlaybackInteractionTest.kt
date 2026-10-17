package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.media3.exoplayer.ExoPlayer
import com.songshu.sstv.data.HisItem
import com.songshu.sstv.data.HistoryStore
import com.songshu.sstv.ui.BottomNav
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
class PlaybackInteractionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("sstv_player", 0).edit().clear().commit()
        HistoryStore.clear(app)
        ThemeStore.setReducedMotion(app, true)
    }
    private fun media() = Intent(app, PlayerActivity::class.java).putExtra("title", "测试影片")
        .putExtra("startPaused", true).putExtra("seek", 34_000L)
        .putStringArrayListExtra("eps", arrayListOf("第一集\u0001file:/first.mp4", "第二集\u0001file:/second.mp4"))
    private fun player(activity: PlayerActivity) = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
    private fun layout(activity: PlayerActivity, width: Int = 786, height: Int = 1702) {
        activity.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        activity.window.decorView.layout(0, 0, width, height)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    @Test fun emptyTabDoesNotRestoreHistoryUnlessEnabledAndRestoresPaused() {
        HistoryStore.put(app, HisItem("file:/last.mp4", "上次影片", "第一集", "", "file:/last.mp4", 42_000, 100_000, 1,
            listOf("第一集\u0001file:/last.mp4")))
        val my = Robolectric.buildActivity(MyActivity::class.java).setup()
        try {
            BottomNav.open(my.get(), PlayerActivity::class.java)
            val emptyIntent = shadowOf(my.get()).nextStartedActivity
            assertNull(emptyIntent.getStringArrayListExtra("eps"))
            val empty = Robolectric.buildActivity(PlayerActivity::class.java, emptyIntent).setup()
            try {
                assertEquals(View.VISIBLE, empty.get().findViewById<View>(R.id.emptyPlayback).visibility)
                assertEquals(View.GONE, empty.get().findViewById<View>(R.id.videoStage).visibility)
                assertFalse(player(empty.get()).playWhenReady)
            } finally { empty.pause().stop().destroy() }
            PlaybackPreferences.setRestoreLast(app, true)
            BottomNav.open(my.get(), PlayerActivity::class.java)
            val restore = shadowOf(my.get()).nextStartedActivity
            assertTrue(restore.getBooleanExtra("startPaused", false))
            assertEquals(42_000, restore.getLongExtra("seek", 0))
            val controller = Robolectric.buildActivity(PlayerActivity::class.java, restore).setup()
            try {
                assertFalse(player(controller.get()).playWhenReady)
                assertEquals(42_000, player(controller.get()).currentPosition)
            } finally { controller.pause().stop().destroy() }
        } finally { my.pause().stop().destroy() }
    }

    @Test fun leavingPlaybackPausesAndReturningDoesNotAutoplay() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        val activity = controller.get()
        try {
            assertFalse(player(activity).playWhenReady)
            activity.findViewById<View>(R.id.btnPlayPause).performClick()
            assertTrue(player(activity).playWhenReady)
            controller.pause()
            assertFalse(player(activity).playWhenReady)
            controller.resume()
            assertFalse(player(activity).playWhenReady)
            controller.newIntent(Intent(app, PlayerActivity::class.java).putExtra("resumeExisting", true))
            assertFalse(player(activity).playWhenReady)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun controlsHaveConditionalNextAndCenteredHorizontalSpeedPicker() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get(); layout(activity)
            val full = activity.findViewById<View>(R.id.btnFull)
            val footer = activity.findViewById<View>(R.id.bottomControls)
            val rect = android.graphics.Rect(); full.getGlobalVisibleRect(rect)
            assertTrue(rect.centerX() > 600)
            assertEquals(footer, full.parent.parent)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnNext).visibility)
            activity.findViewById<View>(R.id.btnSpeed).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            layout(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val scroll = activity.findViewById<HorizontalScrollView>(R.id.speedScroll)
            assertEquals(240, scroll.scrollX)
            layout(activity)
            val options = activity.findViewById<android.widget.LinearLayout>(R.id.speedOptions)
            val normal = options.getChildAt(2)
            assertEquals(scroll.width / 2, normal.left + normal.width / 2 - scroll.scrollX)
            activity.findViewById<View>(R.id.btnEp).performClick()
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
            ShadowDialog.getLatestDialog().dismiss()
            activity.findViewById<View>(R.id.btnNext).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnNext).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fullscreenLockBlocksVideoTouchesUntilUnlocked() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get()
            RuntimeEnvironment.setQualifiers("zh-rCN-w851dp-h393dp-land-xhdpi")
            activity.onConfigurationChanged(activity.resources.configuration); layout(activity, 1702, 786)
            val lock = activity.findViewById<View>(R.id.btnLock)
            assertEquals(View.VISIBLE, lock.visibility)
            lock.performClick()
            assertEquals("解锁屏幕", lock.contentDescription)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.videoControls).visibility)
            val before = activity.window.attributes.screenBrightness
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 200f, 200f, 0)
            assertTrue(activity.dispatchTouchEvent(event)); event.recycle()
            assertEquals(before, activity.window.attributes.screenBrightness)
            lock.performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoControls).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fullscreenSideSwipesAdjustVolumeAndBrightness() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get()
            org.robolectric.shadows.ShadowDisplayManager.changeDisplay(android.view.Display.DEFAULT_DISPLAY, "w851dp-h393dp-land-xhdpi")
            RuntimeEnvironment.setQualifiers("zh-rCN-w851dp-h393dp-land-xhdpi")
            activity.onConfigurationChanged(activity.resources.configuration); layout(activity, 1702, 786)
            val audio = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 3, 0)
            val bounds = android.graphics.Rect()
            assertTrue(activity.findViewById<View>(R.id.videoStage).getGlobalVisibleRect(bounds))
            fun swipe(x: Float) {
                val now = android.os.SystemClock.uptimeMillis()
                val startY = bounds.top + bounds.height() * .55f
                val endY = bounds.top + bounds.height() * .25f
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, startY, 0)
                val move = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_MOVE, x, endY, 0)
                val up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, x, endY, 0)
                activity.dispatchTouchEvent(down)
                val eligible = PlayerActivity::class.java.getDeclaredField("gestureEligible").apply { isAccessible = true }.getBoolean(activity)
                assertTrue("Touch must reach the video: bounds=$bounds x=$x y=$startY", eligible)
                activity.dispatchTouchEvent(move)
                activity.dispatchTouchEvent(up)
                down.recycle(); move.recycle(); up.recycle()
            }
            swipe(bounds.left + bounds.width() * .85f)
            assertTrue("volume=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}, max=${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}, bounds=$bounds",
                audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 3)
            swipe(bounds.left + bounds.width() * .15f)
            assertTrue(activity.window.attributes.screenBrightness > .1f)
        } finally { controller.pause().stop().destroy() }
    }
}
