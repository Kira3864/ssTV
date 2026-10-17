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
    @Test fun renderAdaptiveLauncherIcon() {
        val app = RuntimeEnvironment.getApplication()
        val icon = requireNotNull(app.getDrawable(R.mipmap.ic_launcher))
        org.junit.Assert.assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        org.junit.Assert.assertTrue(app.getDrawable(R.mipmap.ic_launcher_round) is android.graphics.drawable.AdaptiveIconDrawable)
        val directory = File("build/reports/ui").apply { mkdirs() }
        for (size in listOf(48, 192, 512)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            File(directory, "launcher-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun renderPlayerPortraitFullscreenAndEpisodeSheet() {
        val app = RuntimeEnvironment.getApplication()
        ThemeStore.select(app, ThemeStore.styles[2])
        ThemeStore.setReducedMotion(app, true)
        val intent = android.content.Intent(app, PlayerActivity::class.java)
            .putExtra("title", "凡人修仙传").putExtra("startPaused", true)
            .putStringArrayListExtra("eps", ArrayList((1..24).map { "第${it}集\u0001file:/sample$it.mp4" }))
        val controller = org.robolectric.Robolectric.buildActivity(PlayerActivity::class.java, intent).setup()
        val activity = controller.get()
        val directory = File("build/reports/ui").apply { mkdirs() }
        fun capture(view: View, width: Int, height: Int, name: String) {
            fun invalidateLayout(v: View) {
                v.forceLayout()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) invalidateLayout(v.getChildAt(i))
            }
            repeat(2) {
                invalidateLayout(view)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            invalidateLayout(view)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            if (name == "player-speed.png") {
                val slider = activity.findViewById<com.songshu.sstv.ui.PlaybackSpeedSlider>(R.id.speedSlider)
                org.junit.Assert.assertEquals(1f, slider.selectedSpeed, .001f)
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            capture(root, 786, 1702, "player-portrait.png")
            activity.findViewById<View>(R.id.btnSpeed).performClick()
            capture(root, 786, 1702, "player-speed-portrait.png")
            activity.findViewById<View>(R.id.btnSpeed).performClick()
            activity.findViewById<View>(R.id.btnEp).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            capture(dialog.findViewById(android.R.id.content), 746, 1100, "player-episodes.png")
            dialog.dismiss()
            org.robolectric.shadows.ShadowDisplayManager.changeDisplay(android.view.Display.DEFAULT_DISPLAY, "w851dp-h393dp-land-xhdpi")
            RuntimeEnvironment.setQualifiers("zh-rCN-w851dp-h393dp-land-xhdpi")
            activity.onConfigurationChanged(activity.resources.configuration)
            capture(root, 1702, 786, "player-fullscreen.png")
            activity.findViewById<View>(R.id.btnSpeed).performClick()
            capture(root, 1702, 786, "player-speed.png")
            capture(activity.findViewById(R.id.speedPanel), 608, 176, "player-speed-ruler.png")
            activity.findViewById<View>(R.id.btnLock).performClick()
            capture(root, 1702, 786, "player-locked.png")
        } finally { controller.pause().stop().destroy() }
    }

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
