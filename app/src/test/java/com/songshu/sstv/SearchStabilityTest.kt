package com.songshu.sstv

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="zh-rCN-w393dp-h851dp-port-xhdpi")
class SearchStabilityTest {
    private fun layout(root: View) {
        fun force(v: View) { v.forceLayout(); if(v is ViewGroup) for(i in 0 until v.childCount) force(v.getChildAt(i)) }
        force(root); root.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1702,View.MeasureSpec.EXACTLY)); root.layout(0,0,786,1702)
    }
    @Test fun breakingBadResultsFromTwentyProvidersCanReorderWhileScrollingAndSwitchingLayouts() {
        val app = RuntimeEnvironment.getApplication()
        listOf("sstv","display","rankings").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        ThemeStore.setReducedMotion(app,true)
        val fixture = JSONArray(javaClass.classLoader!!.getResourceAsStream("breaking-bad-search.json")!!.bufferedReader().use { it.readText() })
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if(request.requestUrl!!.queryParameter("wd") == null) return MockResponse().setBody("""{"list":[]}""")
                val index = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
                return MockResponse().setBody(fixture.getJSONObject(index).toString()).setBodyDelay((8-index%8)*20L,TimeUnit.MILLISECONDS)
            }
        }; server.start()
        SourceStore.sources(app)
        val sources = (0 until fixture.length()).map { Source("片源$it",server.url("/source/$it").toString()) }
        SourceStore.save(app,sources); SourceStore.setCurrent(app,sources.first())
        val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
        try {
            val a = controller.get(); val root = a.window.decorView; val list = a.findViewById<RecyclerView>(R.id.list)
            a.findViewById<EditText>(R.id.input).setText("绝命毒师"); a.findViewById<View>(R.id.btnGo).performClick()
            repeat(160) { frame ->
                Shadows.shadowOf(Looper.getMainLooper()).idle(); layout(root)
                list.scrollBy(0,if(frame%25<15) 120 else -90)
                if(frame in listOf(3,9,18,27,45,65)) {
                    a.findViewById<View>(R.id.btnViewMode).performClick()
                    ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(if(frame%2==0) 1 else 2,0)
                }
                if(frame in listOf(4,12,24,48)) a.findViewById<View>(R.id.btnSort).performClick()
                assertFalse(a.isFinishing); assertFalse(a.isDestroyed)
                Thread.sleep(15)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle(); layout(root)
            assertTrue("All source groups should arrive",list.adapter!!.itemCount>150)
            assertNull(Shadows.shadowOf(a).nextStartedActivity)
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
}
