package com.songshu.sstv

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class DiscoveryRegressionTest {
    @Test fun rankingSkipsAdultCategoriesAndContinuesToFillTenItems() {
        val app = RuntimeEnvironment.getApplication()
        listOf("sstv","display").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val second = request.requestUrl!!.queryParameter("pg") == "2"
                val adult = """{"vod_id":"adult","vod_name":"成人影片","type_name":"伦理片"}"""
                val data = if (!second) if (request.requestUrl!!.queryParameter("wd") != null) adult else adult + """,{"vod_id":"1","vod_name":"影片1"}"""
                    else (2..10).joinToString(",") { """{"vod_id":"$it","vod_name":"影片$it"}""" }
                return MockResponse().setBody("""{"pagecount":2,"list":[$data]}""")
            }
        }; server.start()
        SourceStore.sources(app); val source = Source("片源",server.url("/api").toString()); SourceStore.save(app,listOf(source)); SourceStore.setCurrent(app,source)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val list = controller.get().findViewById<RecyclerView>(R.id.list); val adapter = list.adapter as SearchResultsAdapter
            await { adapter.itemCount == 11 }
            assertFalse(SourceStore.nsfw(app))
            (1..10).forEach { i -> val holder = adapter.createViewHolder(list,2) as SearchResultsAdapter.Poster; adapter.bindViewHolder(holder,i); assertFalse(holder.name.text.contains("成人")) }
            controller.get().findViewById<EditText>(R.id.input).setText("影片")
            controller.get().findViewById<View>(R.id.btnGo).performClick()
            await { adapter.itemCount == 10 }
            assertTrue(adapter.query == "影片")
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis()+8000
        while (System.currentTimeMillis()<until) { shadowOf(Looper.getMainLooper()).idle(); if (condition()) return; Thread.sleep(20) }
        assertTrue("UI update timed out",condition())
    }
    @Test fun discoveryStartsWithTenRankedListItemsAndNeverFocusesTheSearchInput() {
        val app = RuntimeEnvironment.getApplication()
        listOf("sstv","display").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"pagecount":1,"list":[${(1..14).joinToString(",") { """{"vod_id":"$it","vod_name":"影片$it","vod_hits_week":$it,"vod_time":"2026-10-17"}""" }}]}""")
        }; server.start()
        SourceStore.sources(app); val source = Source("排行源",server.url("/api").toString()); SourceStore.save(app,listOf(source)); SourceStore.setCurrent(app,source)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val activity = controller.get(); val list = activity.findViewById<RecyclerView>(R.id.list); val adapter = list.adapter as SearchResultsAdapter
            await { adapter.itemCount == 11 }
            assertFalse(activity.findViewById<EditText>(R.id.input).hasFocus()); assertTrue(adapter.listMode)
            assertEquals(1,(list.layoutManager as GridLayoutManager).spanCount)
            val holder = adapter.createViewHolder(list,2) as SearchResultsAdapter.Poster; adapter.bindViewHolder(holder,1)
            assertEquals("1. 影片14",holder.name.text.toString())
            assertEquals(View.GONE,activity.findViewById<View>(R.id.btnSort).visibility)
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
    @Test fun subsequentSearchPagesResortAndReverseAndGridControlsWork() {
        val app = RuntimeEnvironment.getApplication()
        listOf("sstv","display").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.queryParameter("wd") == null) return MockResponse().setBody("""{"list":[]}""")
                val second = url.queryParameter("pg") == "2"
                val names = if (second) listOf("第2季","第十季") else listOf("第三季","第一季")
                return MockResponse().setBody("""{"pagecount":2,"list":[${names.mapIndexed { i,n -> """{"vod_id":"${if(second) i+2 else i}","vod_name":"恶搞之家$n"}""" }.joinToString(",")}]}""")
                    .setBodyDelay(if(second) 250 else 10,TimeUnit.MILLISECONDS)
            }
        }; server.start()
        SourceStore.sources(app); val source = Source("测试源",server.url("/api").toString()); SourceStore.save(app,listOf(source)); SourceStore.setCurrent(app,source)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val activity = controller.get(); val list = activity.findViewById<RecyclerView>(R.id.list); val adapter = list.adapter as SearchResultsAdapter
            activity.findViewById<EditText>(R.id.input).setText("恶搞之家"); activity.findViewById<View>(R.id.btnGo).performClick()
            await { adapter.itemCount == 5 }
            fun name(at: Int): String { val holder = adapter.createViewHolder(list,adapter.getItemViewType(at)) as SearchResultsAdapter.Poster; adapter.bindViewHolder(holder,at); return holder.name.text.toString() }
            assertEquals(listOf("第一季","第2季","第三季","第十季").map { "恶搞之家$it" },(1..4).map { name(it) })
            activity.findViewById<View>(R.id.btnSort).performClick(); await { name(1).endsWith("第十季") }
            activity.findViewById<View>(R.id.btnViewMode).performClick()
            ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(2,0)
            assertFalse(adapter.listMode); assertEquals(2,(list.layoutManager as GridLayoutManager).spanCount)
            activity.findViewById<View>(R.id.btnViewMode).performClick(); ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(1,0)
            assertTrue(adapter.listMode); assertEquals(1,(list.layoutManager as GridLayoutManager).spanCount)
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
}
