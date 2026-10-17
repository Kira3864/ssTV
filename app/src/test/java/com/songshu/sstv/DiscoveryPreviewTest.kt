package com.songshu.sstv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryPreviewTest {
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun capture(v: View, name: String, width: Int = 786, height: Int = 1702) {
        repeat(3) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); views(v).forEach { it.forceLayout() }
            v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); v.layout(0,0,width,height)
        }
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); v.draw(Canvas(bitmap))
        File("build/reports/ui").mkdirs(); File("build/reports/ui/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun rankingAndSearchHistoryRenderCleanlyAcrossThemesWithVisibleEpisodeCounts() {
        val app = RuntimeEnvironment.getApplication()
        listOf("sstv","rankings","search_history","display").forEach { app.getSharedPreferences(it,0).edit().clear().commit() }
        SourceStore.sources(app)
        val source = Source("预览片源","https://preview.invalid/api"); SourceStore.save(app,listOf(source)); SourceStore.setCurrent(app,source)
        RankingCache.put(app,source,true,(1..10).map { Vod(id="$it",name="凡人修仙传 第${it}季",remark="完结",totalEpisodes=24,completed=true,typeName="国产动漫",year="2026") })
        (1..15).forEach { SearchHistoryStore.add(app,"搜索记录 $it") }; ThemeStore.setReducedMotion(app,true)
        for (theme in ThemeStore.styles) {
            ThemeStore.select(app,theme)
            val controller = Robolectric.buildActivity(SearchActivity::class.java).setup()
            try {
                val activity = controller.get(); val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                capture(root,"discovery-${theme.id}")
                assertTrue(views(root).filterIsInstance<TextView>().any { it.text.contains("共24集") })
                activity.findViewById<View>(R.id.btnSearchHistory).performClick()
                val dialog = ShadowDialog.getLatestDialog(); capture(dialog.findViewById(android.R.id.content),"search-history-${theme.id}",738,1200)
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "搜索记录 15" })
                dialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        }
    }
}
