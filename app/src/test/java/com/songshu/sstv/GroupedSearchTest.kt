package com.songshu.sstv

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.data.Vod
import com.songshu.sstv.ui.SearchGroup
import com.songshu.sstv.ui.SearchResultsAdapter
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GroupedSearchTest {
    @Test fun sourceGroupsAreSortedByLatencyAndSameTitlesRemainInEachGroup() {
        val one = Source("慢源", "https://slow.example")
        val two = Source("快源", "https://fast.example")
        val rows = SearchResultsAdapter.rows(listOf(
            SearchGroup(one, 800, listOf(Vod(id="1", name="同名影片", sourceApi=one.api))),
            SearchGroup(two, 50, listOf(Vod(id="1", name="同名影片", sourceApi=two.api)))))
        assertEquals(4, rows.size)
        assertEquals(two, rows.first().group!!.source)
        assertEquals(2, rows.count { it.vod?.name == "同名影片" })
    }

    @Test fun concurrentSearchShowsSeparateHeadersInResponseTimeOrder() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sstv", 0).edit().clear().commit()
        SourceStore.sources(app)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"1","vod_name":"同名影片"}]}""")
                .setBodyDelay(if (request.path!!.startsWith("/slow")) 300 else 10, TimeUnit.MILLISECONDS)
        }
        server.start()
        val slow = Source("慢源", server.url("/slow").toString())
        val fast = Source("快源", server.url("/fast").toString())
        SourceStore.save(app, listOf(slow, fast)); SourceStore.setCurrent(app, slow)
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.input).setText("同名影片")
            activity.findViewById<View>(R.id.btnGo).performClick()
            val list = activity.findViewById<RecyclerView>(R.id.list)
            val adapter = list.adapter as SearchResultsAdapter
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline && adapter.itemCount < 4) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
            }
            assertEquals(4, adapter.itemCount)
            val header = adapter.createViewHolder(list, 0) as SearchResultsAdapter.Header
            adapter.bindViewHolder(header, 0)
            assertTrue(header.label.text.toString().startsWith("快源"))
            assertTrue(adapter.isHeader(2))
            assertFalse(adapter.isHeader(1))
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
}
