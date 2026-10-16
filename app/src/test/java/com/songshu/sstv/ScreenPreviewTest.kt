package com.songshu.sstv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Vod
import com.songshu.sstv.ui.VodAdapter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenPreviewTest {
    @Test fun renderHomeThemesForVisualReview() {
        val directory = File("build/reports/ui").apply { mkdirs() }
        ThemeStore.styles.forEach { style ->
            val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), style.resource)
            val view = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
            view.findViewById<TextView>(R.id.sourceLabel).text = "暴风资源 ▾"
            view.findViewById<TextView>(R.id.filterToggle).text = "电影 · 动作片    筛选 ▾"
            val grid = view.findViewById<RecyclerView>(R.id.grid)
            grid.layoutManager = GridLayoutManager(context, 3)
            grid.adapter = VodAdapter(300) {}.apply {
                submit(listOf("火柴盒大电影", "花匠刺客", "最后的旅程", "未完的故事", "海边来信", "漫长的一天", "夏日", "远山", "夜航").mapIndexed { i, title ->
                    Vod(id = "$i", name = title, remark = "HD")
                }, false)
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1702, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 786, 1702)
            val bitmap = Bitmap.createBitmap(786, 1702, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "home-${style.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
