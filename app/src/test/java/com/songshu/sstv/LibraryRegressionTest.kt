package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import androidx.media3.exoplayer.ExoPlayer
import com.songshu.sstv.data.*
import com.songshu.sstv.cache.*
import com.songshu.sstv.ui.*
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
class LibraryRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val media = MediaRef("123", "凡人修仙传", "", "https://a.example/api", "片源甲",
        listOf("第3集\u0001file:/third.mp4", "第4集\u0001file:/fourth.mp4"))
    @Before fun reset() {
        listOf("library", "sstv", "sstv_player", "display").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        ThemeStore.setReducedMotion(app, true)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun layout(activity: android.app.Activity) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView; views(root).forEach { it.forceLayout() }
        root.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1702,View.MeasureSpec.EXACTLY)); root.layout(0,0,786,1702)
    }
    @Test fun blankNamesAreGeneratedAndProviderIdentityIsUniqueAcrossFolders() {
        val first = LibraryStore.groups(app).single()
        val second = LibraryStore.createGroup(app,"", "我的备注")
        assertEquals("收藏2", second.name); assertEquals("我的备注", second.note)
        assertTrue(LibraryStore.addFavorite(app,media,first.id))
        assertFalse(LibraryStore.addFavorite(app,media,second.id))
        assertTrue(LibraryStore.addFavorite(app,media.copy(api="https://b.example",source="片源乙"),second.id))
        LibraryStore.moveFavorite(app,media.key,second.id)
        assertEquals(second.id,LibraryStore.favorites(app).first { it.media.key == media.key }.group)
        LibraryStore.deleteGroup(app,second.id)
        assertEquals(2,LibraryStore.favorites(app).size)
        assertTrue(LibraryStore.favorites(app).all { it.group == first.id })
    }
    @Test fun likesKeepRepeatedPointsAndCacheAndOnlineEpisodesShareAHierarchy() {
        LibraryStore.like(app,media,320_000); LibraryStore.like(app,media,517_000); LibraryStore.like(app,media,320_000)
        val cached = media.copy(eps=listOf("第3集\u0001file:/cache/third.mp4"))
        LibraryStore.like(app,cached,200_000)
        val marks = LibraryStore.likes(app)
        assertEquals(4,marks.map { it.id }.distinct().size)
        assertEquals(1,marks.groupBy { it.media.key }.size)
        assertEquals(1,marks.groupBy { it.media.episode }.size)
        assertEquals(2,marks.count { it.position == 320_000L })
        val intent = marks.first { it.position == 517_000L }.media.playIntent(app,517_000)
        assertEquals(517_000, intent.getLongExtra("seek",0)); assertEquals(0,intent.getIntExtra("index",-1))
        LibraryStore.removeLike(app,marks.first().id); assertEquals(3,LibraryStore.likes(app).size)
    }
    @Test fun folderNavigationAndTimestampNavigationOpenActualPages() {
        val group = LibraryStore.groups(app).single(); LibraryStore.addFavorite(app,media,group.id)
        val root = Robolectric.buildActivity(LibraryActivity::class.java).setup()
        try {
            layout(root.get()); val list = views(root.get().window.decorView).filterIsInstance<RecyclerView>().single()
            list.getChildAt(0).performClick()
            val intent = shadowOf(root.get()).nextStartedActivity
            assertEquals(group.id,intent.getStringExtra("group"))
            val folder = Robolectric.buildActivity(LibraryActivity::class.java,intent).setup()
            try { layout(folder.get()); assertEquals(1,views(folder.get().window.decorView).filterIsInstance<RecyclerView>().single().adapter!!.itemCount) }
            finally { folder.pause().stop().destroy() }
        } finally { root.pause().stop().destroy() }
        LibraryStore.like(app,media,320_000); LibraryStore.like(app,media,517_000)
        val series = Robolectric.buildActivity(LibraryActivity::class.java,Intent(app,LibraryActivity::class.java).putExtra("likes",true)).setup()
        try {
            layout(series.get()); views(series.get().window.decorView).filterIsInstance<RecyclerView>().single().getChildAt(0).performClick()
            val eps = Robolectric.buildActivity(LibraryActivity::class.java,shadowOf(series.get()).nextStartedActivity).setup()
            try {
                layout(eps.get()); views(eps.get().window.decorView).filterIsInstance<RecyclerView>().single().getChildAt(0).performClick()
                val points = Robolectric.buildActivity(LibraryActivity::class.java,shadowOf(eps.get()).nextStartedActivity).setup()
                try {
                    layout(points.get()); val list = views(points.get().window.decorView).filterIsInstance<RecyclerView>().single()
                    assertEquals(2,list.adapter!!.itemCount); list.getChildAt(1).performClick()
                    val play = shadowOf(points.get()).nextStartedActivity
                    assertEquals(PlayerActivity::class.java.name,play.component!!.className); assertEquals(517_000,play.getLongExtra("seek",0))
                    assertFalse(play.getBooleanExtra("startPaused",true))
                } finally { points.pause().stop().destroy() }
            } finally { eps.pause().stop().destroy() }
        } finally { series.pause().stop().destroy() }
    }
    @Test fun playerClearRequiresConfirmationRemovesOnlyThisSourceAndNeverRestoresAnotherHistory() {
        val local = media.copy(api="",source="离线")
        HistoryStore.put(app,HisItem("one",local.title,"第3集","","file:/third.mp4",30000,0,1,local.eps))
        HistoryStore.put(app,HisItem("two",local.title,"第3集","","https://other/three",30000,0,2,emptyList(),"https://b.example","片源乙","123"))
        val controller = Robolectric.buildActivity(PlayerActivity::class.java,local.playIntent(app,320_000).putExtra("startPaused",true)).setup()
        val activity = controller.get()
        try {
            activity.findViewById<View>(R.id.btnClearPlayback).performClick()
            assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.videoStage).visibility)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(-1).performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.emptyPlayback).visibility)
            assertEquals(1,HistoryStore.list(app).size); assertEquals("片源乙",HistoryStore.list(app).single().srcName)
            controller.pause().resume(); assertEquals(1,HistoryStore.list(app).size)
            BottomNav.open(activity,PlayerActivity::class.java)
            assertNull(shadowOf(activity).nextStartedActivity.getStringArrayListExtra("eps"))
            assertTrue(PlaybackPreferences.cleared(app))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun cacheFolderIncludesASingleDownloadedEpisodeFromASeriesAndSeparatesProviders() {
        val ep = DlItem("u","剧集","第3集","/three",10,1,api="https://a",sourceName="甲",vid="1",series=true)
        val film = DlItem("v","电影","正片","/film",10,1,api="https://a",sourceName="甲",vid="2")
        val other = ep.copy(api="https://b",sourceName="乙")
        val rows = CacheGroups.rows(listOf(ep, film, other),true)
        assertEquals(3,rows.size); assertEquals(2,rows.count { it.folder })
        DlStore.add(app,ep); DlStore.add(app,other); assertEquals(2,DlStore.list(app).size)
        DlStore.remove(app,ep.url,ep.api); assertEquals("乙",DlStore.list(app).single().sourceName)
    }
    @Test fun nsfwDefaultsOffAndMigratesExplicitOldPreferences() {
        assertFalse(SourceStore.nsfw(app)); assertTrue(SourceStore.filterAdult(app))
        SourceStore.setFilterAdult(app,false); assertTrue(SourceStore.nsfw(app))
        SourceStore.setNsfw(app,false); assertTrue(SourceStore.filterAdult(app))
    }
    @Test fun displayPreferencesPersistAndClampColumns() {
        val prefs = DisplayPrefs(app,"favorites"); prefs.columns = 100; prefs.grid = false
        val restored = DisplayPrefs(app,"favorites"); assertEquals(8,restored.columns); assertFalse(restored.grid)
        restored.columns = 1; assertEquals(2,prefs.columns)
    }
    @Test fun timestampAtZeroOverridesAnOlderPlaybackPosition() {
        val local = media.copy(api="")
        HistoryStore.put(app,HisItem("file:/third.mp4",local.title,"第3集","","file:/third.mp4",80000,180000,1,local.eps))
        val controller = Robolectric.buildActivity(PlayerActivity::class.java,local.playIntent(app,0).putExtra("startPaused",true)).setup()
        try {
            val player = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(controller.get()) as ExoPlayer
            assertEquals(0,player.currentPosition)
        } finally { controller.pause().stop().destroy() }
    }
}
