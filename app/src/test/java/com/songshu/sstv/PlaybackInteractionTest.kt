package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.songshu.sstv.ui.PlaybackSpeedSlider
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
        shadowOf(Looper.getMainLooper()).idle()
        fun force(view: View) {
            view.forceLayout()
            if (view is ViewGroup) for (i in 0 until view.childCount) force(view.getChildAt(i))
        }
        val decor = activity.window.decorView
        force(decor)
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
    }

    @Test fun emptyTabDoesNotRestoreHistoryUnlessEnabledAndRestoresPaused() {
        PlaybackPreferences.setRestoreLast(app, false)
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
            val slider = activity.findViewById<PlaybackSpeedSlider>(R.id.speedSlider)
            assertEquals(1f, slider.selectedSpeed, .001f)
            val bounds = android.graphics.Rect()
            assertTrue(slider.getGlobalVisibleRect(bounds))
            val now = android.os.SystemClock.uptimeMillis()
            val endX = bounds.exactCenterX() - 96 * activity.resources.displayMetrics.density
            fun event(action: Int, x: Float, time: Long) = MotionEvent.obtain(now, time, action, x + 28f, bounds.exactCenterY() + 56f, 0).apply { offsetLocation(-28f, -56f) }
            val down = event(MotionEvent.ACTION_DOWN, bounds.exactCenterX(), now)
            val move = event(MotionEvent.ACTION_MOVE, endX, now + 200)
            val up = event(MotionEvent.ACTION_UP, endX, now + 600)
            activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(move)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
            assertEquals("Dragging must not commit or snap while the finger is held", 1f, slider.selectedSpeed, .001f)
            activity.dispatchTouchEvent(up)
            down.recycle(); move.recycle(); up.recycle()
            assertEquals(1.5f, slider.selectedSpeed, .001f)
            assertEquals(1.5f, player(activity).playbackParameters.speed, .001f)
            activity.findViewById<View>(R.id.btnEp).performClick()
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
            ShadowDialog.getLatestDialog().dismiss()
            activity.findViewById<View>(R.id.btnNext).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnNext).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    private fun tap(activity: PlayerActivity, target: View, screenOffsetX: Float = 0f, screenOffsetY: Float = 0f) {
        val bounds = android.graphics.Rect()
        assertTrue("Target must be visible: ${target.contentDescription}", target.getGlobalVisibleRect(bounds))
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX() + screenOffsetX, bounds.exactCenterY() + screenOffsetY, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX() + screenOffsetX, bounds.exactCenterY() + screenOffsetY, 0)
        down.offsetLocation(-screenOffsetX, -screenOffsetY)
        up.offsetLocation(-screenOffsetX, -screenOffsetY)
        activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
        shadowOf(Looper.getMainLooper()).idle()
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        layout(activity, if (landscape) 1702 else 786, if (landscape) 786 else 1702)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-xhdpi")
    fun lockButtonStillWorksWhenFullscreenWindowHasAScreenOffset() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get(); layout(activity, 1702, 786)
            val lock = activity.findViewById<View>(R.id.btnLock)
            tap(activity, lock, 82f, 28f)
            assertEquals("解锁屏幕", lock.contentDescription)
            tap(activity, activity.findViewById(R.id.btnPlayPause), 82f, 28f)
            assertTrue(player(activity).playWhenReady)
            tap(activity, lock, 82f, 28f)
            assertEquals("锁定屏幕", lock.contentDescription)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-xhdpi")
    fun fullscreenLockRespondsToTouchesAndKeepsPauseExitAndUnlockAvailable() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get()
            activity.onConfigurationChanged(activity.resources.configuration); layout(activity, 1702, 786)
            val lock = activity.findViewById<View>(R.id.btnLock)
            assertEquals(View.VISIBLE, lock.visibility)
            assertEquals("锁定屏幕", lock.contentDescription)
            tap(activity, lock)
            assertEquals("解锁屏幕", lock.contentDescription)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoControls).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.topBar).visibility)
            listOf(R.id.btnNext, R.id.btnEp, R.id.btnSpeed).forEach {
                assertEquals(View.GONE, activity.findViewById<View>(it).visibility)
            }
            assertFalse(activity.findViewById<View>(R.id.playerSeek).isEnabled)
            layout(activity, 1702, 786)
            val pause = activity.findViewById<View>(R.id.btnPlayPause)
            tap(activity, pause)
            assertTrue(player(activity).playWhenReady)
            tap(activity, pause)
            assertFalse(player(activity).playWhenReady)
            tap(activity, lock)
            assertEquals("锁定屏幕", lock.contentDescription)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.topBar).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnEp).visibility)
            tap(activity, lock)
            layout(activity, 1702, 786)
            tap(activity, activity.findViewById(R.id.btnFull))
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertEquals("锁定屏幕", lock.contentDescription)
            // The system back action also leaves fullscreen while locked.
            tap(activity, lock)
            activity.onBackPressed()
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertEquals("锁定屏幕", lock.contentDescription)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-xhdpi")
    fun fullscreenLockBlocksSwipesSeekingAndVideoDoubleTaps() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get()
            activity.onConfigurationChanged(activity.resources.configuration); layout(activity, 1702, 786)
            val audio = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 3, 0)
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = .6f }
            tap(activity, activity.findViewById(R.id.btnLock))
            layout(activity, 1702, 786)
            val bounds = android.graphics.Rect()
            assertTrue(activity.findViewById<View>(R.id.videoStage).getGlobalVisibleRect(bounds))
            val position = player(activity).currentPosition
            fun gesture(x1: Float, y1: Float, x2: Float, y2: Float) {
                val now = android.os.SystemClock.uptimeMillis()
                listOf(
                    MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1, y1, 0),
                    MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_MOVE, x2, y2, 0),
                    MotionEvent.obtain(now, now + 120, MotionEvent.ACTION_UP, x2, y2, 0)
                ).forEach { activity.dispatchTouchEvent(it); it.recycle() }
            }
            for (fraction in listOf(.25f, .75f)) {
                val x = bounds.left + bounds.width() * fraction
                gesture(x, bounds.top + bounds.height() * .65f, x, bounds.top + bounds.height() * .2f)
            }
            gesture(bounds.left + bounds.width() * .3f, bounds.exactCenterY(), bounds.left + bounds.width() * .7f, bounds.exactCenterY())
            repeat(2) { gesture(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterX(), bounds.exactCenterY()) }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertFalse(player(activity).playWhenReady)
            assertEquals(position, player(activity).currentPosition)
            assertEquals(3, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            assertEquals(.6f, activity.window.attributes.screenBrightness, .001f)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.hudBox).visibility)
            assertEquals("解锁屏幕", activity.findViewById<View>(R.id.btnLock).contentDescription)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w851dp-h393dp-land-xhdpi")
    fun fullscreenSideSwipesAdjustVolumeAndBrightness() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, media()).setup()
        try {
            val activity = controller.get()
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
