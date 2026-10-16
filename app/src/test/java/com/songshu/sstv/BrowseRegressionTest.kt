package com.songshu.sstv

import android.app.Application
import android.os.Looper
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.ui.VodAdapter
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
class BrowseRegressionTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("The browsing state did not settle before the deadline")
    }

    @Test fun changingCategoryDuringLoadKeepsTheNewestSelection() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sstv", 0).edit().clear().commit()
        SourceStore.sources(app)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.queryParameter("ac") == "list") {
                    return MockResponse().setBody("""{"class":[{"type_id":1,"type_name":"电影"},{"type_id":6,"type_pid":1,"type_name":"动作"},{"type_id":7,"type_pid":1,"type_name":"喜剧"}]}""")
                }
                val type = request.requestUrl?.queryParameter("t")
                val response = MockResponse().setBody("""{"page":1,"pagecount":1,"list":[{"vod_id":"$type","vod_name":"影片$type"}]}""")
                return if (type == "6") response.setBodyDelay(500, TimeUnit.MILLISECONDS) else response
            }
        }
        server.start()
        val source = Source("测试片源", server.url("/api.php/provide/vod").toString())
        SourceStore.save(app, listOf(source))
        SourceStore.setCurrent(app, source)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val sub = activity.findViewById<LinearLayout>(R.id.subRow)
            await { sub.childCount == 2 && server.requestCount >= 2 }
            activity.findViewById<android.view.View>(R.id.filterToggle).performClick()
            assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.filterPanel).visibility)
            sub.getChildAt(1).performClick()
            val grid = activity.findViewById<RecyclerView>(R.id.grid)
            val adapter = grid.adapter as VodAdapter
            await { server.requestCount >= 3 && adapter.itemCount == 1 }
            Thread.sleep(650)
            shadowOf(Looper.getMainLooper()).idle()
            val holder = adapter.createViewHolder(grid, 0)
            adapter.bindViewHolder(holder, 0)
            assertEquals("影片7", holder.name.text.toString())
            assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.hint).visibility)
        } finally {
            controller.pause().stop().destroy()
            server.shutdown()
        }
    }
}
