package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.graphics.*
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.util.LruCache
import com.songshu.sstv.data.*
import com.songshu.sstv.ui.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryPreviewTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun capture(intent: Intent, name: String, width: Int = 786, height: Int = 1702) {
        val controller = Robolectric.buildActivity(LibraryActivity::class.java,intent).setup()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            fun force(view: View) { view.forceLayout(); if (view is ViewGroup) for (i in 0 until view.childCount) force(view.getChildAt(i)) }
            repeat(3) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); force(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height)
            }
            assertEquals(width,root.width)
            val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
            File("build/reports/ui").mkdirs(); File("build/reports/ui/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun renderLibraryFoldersListsAndTimestampPagesAcrossThemesAndEightColumns() {
        app.getSharedPreferences("library",0).edit().clear().commit(); app.getSharedPreferences("display",0).edit().clear().commit()
        ThemeStore.setReducedMotion(app,true)
        @Suppress("UNCHECKED_CAST") val cache = Img::class.java.getDeclaredField("cache").apply { isAccessible=true }.get(Img) as LruCache<String,Bitmap>
        val colors = listOf(0xFF42655C.toInt(),0xFF834B40.toInt(),0xFF3F547A.toInt(),0xFFB08650.toInt())
        val movies = (0..11).map { i ->
            val bitmap = Bitmap.createBitmap(200,280,Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap)
            canvas.drawColor(colors[i%4]); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha=50 }
            canvas.drawCircle(160f,60f,100f,paint); paint.alpha=255; paint.textSize=56f; canvas.drawText("${i+1}",24f,235f,paint)
            cache.put("preview:$i",bitmap)
            MediaRef("$i",listOf("凡人修仙传","星际穿越","山海之间","夏日来信")[i%4],"preview:$i","https://provider","片源甲",listOf("第3集\u0001file:/sample.mp4"))
        }
        val group = LibraryStore.groups(app).single()
        movies.forEach { LibraryStore.addFavorite(app,it,group.id) }
        LibraryStore.createGroup(app,"周末电影","留给周末的故事")
        LibraryStore.like(app,movies[0],320_000); LibraryStore.like(app,movies[0],517_000)
        for (theme in ThemeStore.styles) {
            ThemeStore.select(app,theme)
            DisplayPrefs(app,"favorites").grid=true; DisplayPrefs(app,"favorites").columns=2
            capture(Intent(app,LibraryActivity::class.java),"library-${theme.id}-folders")
            capture(Intent(app,LibraryActivity::class.java).putExtra("group",group.id),"library-${theme.id}-grid")
        }
        ThemeStore.select(app,ThemeStore.styles.first { it.id == "paper" })
        DisplayPrefs(app,"favorites").grid=false
        capture(Intent(app,LibraryActivity::class.java).putExtra("group",group.id),"library-paper-list")
        DisplayPrefs(app,"favorites").grid=true; DisplayPrefs(app,"favorites").columns=8
        capture(Intent(app,LibraryActivity::class.java).putExtra("group",group.id),"library-paper-eight-columns")
        capture(Intent(app,LibraryActivity::class.java).putExtra("likes",true).putExtra("media",movies[0].key).putExtra("episode","第3集"),"likes-paper-points")
    }
}
