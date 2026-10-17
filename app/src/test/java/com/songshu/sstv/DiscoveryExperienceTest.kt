package com.songshu.sstv

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class DiscoveryExperienceTest {
    @Test fun homePrefetchAndDiscoveryShareOneRequestAndUnsubscribedListenersStayQuiet() {
        val started = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown(); release.await(5,TimeUnit.SECONDS)
                return MockResponse().setBody("""{"list":[${(1..10).joinToString(",") { """{"vod_id":"$it","vod_name":"影片$it"}""" }}]}""")
            }
        }; server.start(); val source = source(server)
        val removedListener = java.util.concurrent.atomic.AtomicBoolean(false)
        val unsubscribe = RankingLoader.load(app,source,true,changed={ removedListener.set(true) })
        assertTrue(started.await(4,TimeUnit.SECONDS)); unsubscribe()
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            assertEquals(1,server.requestCount); release.countDown()
            val adapter = controller.get().findViewById<RecyclerView>(R.id.list).adapter!!
            await { adapter.itemCount == 11 }; await { RankingCache.read(app,source,true)?.fresh == true }
            assertEquals(1,server.requestCount); assertFalse(removedListener.get())
        } finally { release.countDown(); controller.pause().stop().destroy(); server.shutdown() }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { listOf("sstv","display","rankings","search_history").forEach { app.getSharedPreferences(it,0).edit().clear().commit() } }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis()+7000
        while (System.currentTimeMillis()<until) { Shadows.shadowOf(Looper.getMainLooper()).idle(); if(condition()) return; Thread.sleep(15) }
        assertTrue(condition())
    }
    private fun source(server: MockWebServer): Source {
        val source = Source("测试源",server.url("/api").toString()); SourceStore.sources(app); SourceStore.save(app,listOf(source)); SourceStore.setCurrent(app,source); return source
    }
    @Test fun allConfigurableViewsDefaultToThreeColumnsAndPreserveExplicitChoices() {
        for (scope in listOf("home","search","favorites","likes")) {
            val prefs = DisplayPrefs(app,scope); assertTrue(prefs.grid); assertEquals(3,prefs.columns)
            prefs.grid=false; prefs.columns=5
            val restored = DisplayPrefs(app,scope); assertFalse(restored.grid); assertEquals(5,restored.columns)
        }
    }
    @Test fun historyKeepsFifteenMostRecentUniqueTermsAndCanDeleteOrClear() {
        (1..18).forEach { SearchHistoryStore.add(app,"影片$it") }
        assertEquals(15,SearchHistoryStore.list(app).size); assertEquals("影片18",SearchHistoryStore.list(app).first())
        assertFalse(SearchHistoryStore.list(app).contains("影片1"))
        SearchHistoryStore.add(app,"  影片10  "); assertEquals("影片10",SearchHistoryStore.list(app).first()); assertEquals(15,SearchHistoryStore.list(app).size)
        SearchHistoryStore.remove(app,"影片10"); assertEquals(14,SearchHistoryStore.list(app).size)
        SearchHistoryStore.add(app," "); assertEquals(14,SearchHistoryStore.list(app).size)
        SearchHistoryStore.clear(app); assertTrue(SearchHistoryStore.list(app).isEmpty())
    }
    @Test fun rankingCachesAreSeparatedBySourceAndFilterAndEmptyResultsInvalidateOnlyTheirOwnKey() {
        val one = Source("源甲","https://a.example"); val two = Source("源乙","https://b.example")
        val movie = Vod(id="1",name="电影")
        RankingCache.put(app,one,true,listOf(movie)); RankingCache.put(app,one,false,listOf(movie.copy(name="包含成人")))
        RankingCache.put(app,two,true,listOf(movie.copy(name="其他片源")))
        assertEquals("电影",RankingCache.read(app,one,true)!!.items.single().name)
        assertEquals("包含成人",RankingCache.read(app,one,false)!!.items.single().name)
        RankingCache.put(app,one,true,emptyList())
        assertNull(RankingCache.read(app,one,true)); assertNotNull(RankingCache.read(app,one,false)); assertNotNull(RankingCache.read(app,two,true))
    }
    @Test fun freshTopTenCacheIsImmediateAndViewMenuOnlyChangesSearchResults() {
        val server = MockWebServer(); server.start(); val source = source(server)
        val movies = (1..10).map { Vod(id="$it",name="影片$it",sourceApi=source.api,sourceName=source.name) }
        RankingCache.put(app,source,true,movies)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val a = controller.get(); val list = a.findViewById<RecyclerView>(R.id.list); val adapter = list.adapter as SearchResultsAdapter
            assertEquals(11,adapter.itemCount); assertEquals(0,server.requestCount); assertEquals(View.GONE,a.findViewById<View>(R.id.progress).visibility)
            a.findViewById<View>(R.id.btnViewMode).performClick(); ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(15,0)
            assertTrue(adapter.listMode); assertEquals(1,(list.layoutManager as GridLayoutManager).spanCount)
            assertTrue(DisplayPrefs(app,"search").grid); assertEquals(5,DisplayPrefs(app,"search").columns)
            server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"影片1"}]}"""))
            a.findViewById<EditText>(R.id.input).setText("影片"); a.findViewById<View>(R.id.btnGo).performClick()
            await { adapter.itemCount == 2 }; assertFalse(adapter.listMode); assertEquals(5,(list.layoutManager as GridLayoutManager).spanCount)
            assertEquals(listOf("影片"),SearchHistoryStore.list(app))
            a.findViewById<EditText>(R.id.input).setText(""); a.findViewById<View>(R.id.btnGo).performClick()
            await { adapter.itemCount == 11 }; assertTrue(adapter.listMode); assertEquals(1,(list.layoutManager as GridLayoutManager).spanCount)
            assertEquals(1,server.requestCount)
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
    @Test fun firstRankingPageAppearsBeforeTheSlowSecondPage() {
        val server = MockWebServer(); val waiting = java.util.concurrent.CountDownLatch(1); val allowSecond = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.requestUrl!!.queryParameter("pg") == "2") {
                    waiting.countDown()
                    allowSecond.await(5,TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"pagecount":2,"list":[${(2..10).joinToString(",") { """{"vod_id":"$it","vod_name":"影片$it"}""" }}]}""")
                }
                return MockResponse().setBody("""{"pagecount":2,"list":[{"vod_id":"1","vod_name":"影片1"}]}""")
            }
        }; server.start(); source(server)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val a = controller.get(); val adapter = a.findViewById<RecyclerView>(R.id.list).adapter!!
            assertTrue(waiting.await(4,TimeUnit.SECONDS)); await { adapter.itemCount >= 2 }
            assertEquals(2,adapter.itemCount); assertEquals(View.GONE,a.findViewById<View>(R.id.progress).visibility)
            allowSecond.countDown()
            await { adapter.itemCount == 11 }
        } finally { allowSecond.countDown(); controller.pause().stop().destroy(); server.shutdown() }
    }
    @Test fun staleRankingCacheStaysVisibleWhileRefreshingAndHistorySelectionStartsSearch() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"list":[{"vod_id":"new","vod_name":"新影片"}]}""").setBodyDelay(250,TimeUnit.MILLISECONDS)
        }; server.start(); val source = source(server)
        RankingCache.put(app,source,true,listOf(Vod(id="old",name="旧影片")))
        val prefs = app.getSharedPreferences("rankings",0)
        val key = prefs.all.keys.single(); val json = org.json.JSONObject(prefs.getString(key,null)!!).put("time",1L)
        prefs.edit().putString(key,json.toString()).commit()
        SearchHistoryStore.add(app,"影片")
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val a = controller.get(); val list = a.findViewById<RecyclerView>(R.id.list); val adapter = list.adapter as SearchResultsAdapter
            assertEquals(2,adapter.itemCount)
            val holder = adapter.createViewHolder(list,2) as SearchResultsAdapter.Poster; adapter.bindViewHolder(holder,1); assertTrue(holder.name.text.contains("旧影片"))
            a.findViewById<View>(R.id.btnSearchHistory).performClick()
            fun views(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            val dialog = ShadowDialog.getLatestDialog()
            views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == "影片" }.performClick()
            assertFalse(dialog.isShowing); assertEquals("影片",a.findViewById<EditText>(R.id.input).text.toString())
            assertFalse(adapter.ranked); await { adapter.itemCount == 2 }
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
    @Test fun completedEpisodeCountsUseMetadataOrActualPlaylistsWithoutCountingMoviesAsSeries() {
        val episodes = (1..12).map { Episode("第${it}集","https://example/$it") }
        assertEquals("已完结 · 共12集",Vod(remark="完结",lines=listOf(PlayLine("线路",episodes))).episodeStatus)
        assertEquals("已完结 · 共24集",Vod(totalEpisodes=24,completed=true,typeName="电视剧").episodeStatus)
        assertEquals("已完结 · 共21集",Vod(remark="全二十一集").episodeStatus)
        assertEquals("更新至12集",Vod(remark="更新至12集",totalEpisodes=24).episodeStatus)
        assertEquals("HD",Vod(remark="HD",completed=true,typeName="电影",lines=listOf(PlayLine("高清",episodes.take(1)))).episodeStatus)
        assertEquals("已完结",Vod(completed=true,typeName="电视剧").episodeStatus)
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"完结剧","type_name":"国产剧","vod_remarks":"完结","vod_serial":"36"}]}""")); server.start()
        try { assertEquals("已完结 · 共36集",VodApi.detail(server.url("/api").toString(),"1","片源")!!.episodeStatus) }
        finally { server.shutdown() }
    }
}
