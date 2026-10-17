package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.*
import com.google.common.collect.ImmutableList
import com.songshu.sstv.cache.TransferBudget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="zh-rCN-w393dp-h851dp-port-xhdpi")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQualityTest {
    @Test fun qualitiesOnlyIncludeSupportedVideoTracksWithoutDuplicateResolutions() {
        val formats=listOf(360,720,720,1080,2160).mapIndexed { i,h -> Format.Builder().setId("$i").setSampleMimeType("video/avc")
            .setWidth(h*16/9).setHeight(h).setAverageBitrate((i+1)*500000).build() }
        val group=TrackGroup(*formats.toTypedArray())
        val tracks=Tracks(ImmutableList.of(Tracks.Group(group,true,intArrayOf(C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_UNSUPPORTED_TYPE),booleanArrayOf(true,false,false,false,false))))
        val choices=PlaybackQuality.choices(tracks)
        assertEquals(listOf("1080p","720p","360p"),choices.map { it.label })
        assertEquals(2,choices[1].override.trackIndices.single().toInt())
        assertTrue(PlaybackQuality.choices(Tracks.EMPTY).isEmpty())
    }
    @Test fun playingACachedOnlineVideoKeepsTheOnlineUrlAvailableForCasting() {
        val app=RuntimeEnvironment.getApplication()
        val server=okhttp3.mockwebserver.MockWebServer(); server.start()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(ByteArray(4096))))
        val url=server.url("/cached.mp4").toString()
        try {
            val cached=com.songshu.sstv.cache.Downloader.download(app,"缓存电影","正片",url,api="https://provider")!!
            val intent=Intent(app,PlayerActivity::class.java).putStringArrayListExtra("eps",arrayListOf("正片\u0001$url")).putExtra("api","https://provider").putExtra("startPaused",true)
            val c=Robolectric.buildActivity(PlayerActivity::class.java,intent).setup()
            try {
                val a=c.get()
                repeat(30) { Thread.sleep(10); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
                val resolved=PlayerActivity::class.java.getDeclaredField("resolvedUrl").apply { isAccessible=true }.get(a)
                val player=PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible=true }.get(a) as androidx.media3.exoplayer.ExoPlayer
                assertEquals(url,resolved)
                assertEquals(android.net.Uri.fromFile(cached),player.currentMediaItem!!.localConfiguration!!.uri)
                assertEquals(1,server.requestCount)
            } finally { c.pause().stop().destroy() }
        } finally { server.shutdown() }
    }

    @Test fun qualityButtonFitsPortraitAndIsDisabledByLockWithoutChangingPauseAndFullscreen() {
        val intent=Intent(RuntimeEnvironment.getApplication(),PlayerActivity::class.java).putStringArrayListExtra("eps",arrayListOf("正片\u0001file:/video.mp4")).putExtra("startPaused",true)
        val controller=Robolectric.buildActivity(PlayerActivity::class.java,intent).setup()
        try {
            val a=controller.get(); val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1702,View.MeasureSpec.EXACTLY)); root.layout(0,0,786,1702)
            val quality=a.findViewById<View>(R.id.btnQuality); val full=a.findViewById<View>(R.id.btnFull)
            assertTrue(quality.isShown); assertTrue(quality.right<= (quality.parent as View).width); assertTrue(full.right<= (full.parent as View).width)
            quality.performClick(); assertTrue(ShadowDialog.getLatestDialog().isShowing); ShadowDialog.getLatestDialog().dismiss()
            val lock=PlayerActivity::class.java.getDeclaredMethod("setLocked",Boolean::class.javaPrimitiveType).apply { isAccessible=true }
            lock.invoke(a,true); assertEquals(View.GONE,quality.visibility)
            assertTrue(a.findViewById<View>(R.id.btnPlayPause).isEnabled); assertTrue(full.isEnabled)
            lock.invoke(a,false); assertEquals(View.VISIBLE,quality.visibility)
            assertFalse(TransferBudget.playbackActive)
        } finally { controller.pause().stop().destroy() }
    }
}
