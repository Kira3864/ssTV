package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.songshu.sstv.cache.*

import org.junit.Assert.*
import org.junit.After
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
class CachePlaybackPreviewTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun capture(v: View,name: String,width: Int=786,height: Int=1702) {
        repeat(3) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); all(v).forEach { it.forceLayout() }
            v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            v.layout(0,0,width,height)
        }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); v.draw(Canvas(bitmap))
        File("build/reports/ui").mkdirs(); File("build/reports/ui/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @After fun clearTasks() {
        DlHub.list().forEach { DlHub.remove(it) }
        RuntimeEnvironment.getApplication().getSharedPreferences("download_tasks",0).edit().clear().commit()
        DlHub.init(RuntimeEnvironment.getApplication())
        TransferBudget.playbackActive=false
    }
    @Test fun cacheFoldersAndEpisodeControlsRenderAcrossThemes() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("download_tasks",0).edit().clear().commit(); app.getSharedPreferences("sstv",0).edit().clear().commit()
        DlHub.init(app)
        val file=File(app.filesDir,"preview.ts").apply { writeBytes(ByteArray(4096)) }
        DlStore.add(app,DlItem("u","凡人修仙传","第1集",file.path,file.length(),1,api="https://a",sourceName="暴风资源",vid="1",series=true))
        DlHub.create("凡人修仙传","",(2..15).map { "第${it}集" to "https://a/$it.mp4" },"https://a","暴风资源","1",true)
        DlHub.list().forEachIndexed { i,t -> DlHub.pause(t); t.curPercent=(i+1)*5; t.doneBytes=(i+1)*1024*1024L; t.totalBytes=100*1024*1024L }
        for(theme in ThemeStore.styles) {
            ThemeStore.select(app,theme)
            val root=Robolectric.buildActivity(CacheActivity::class.java).setup()
            try {
                capture(root.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0),"cache-folders-${theme.id}")
                val folder=Robolectric.buildActivity(CacheActivity::class.java,Intent(app,CacheActivity::class.java).putExtra("folder","https://a\u00011")).setup()
                try { capture(folder.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0),"cache-episodes-${theme.id}") }
                finally { folder.pause().stop().destroy() }
            } finally { root.pause().stop().destroy() }
        }
    }
    @Test fun qualityAndFullscreenControlsFitNarrowPortraitAndLandscape() {
        val app=RuntimeEnvironment.getApplication()
        for(land in listOf(false,true)) {
            RuntimeEnvironment.setQualifiers(if(land) "zh-rCN-w851dp-h393dp-land-xhdpi" else "zh-rCN-w360dp-h800dp-port-xhdpi")
            val intent=Intent(app,PlayerActivity::class.java).putStringArrayListExtra("eps",arrayListOf("第1集\u0001file:/one.mp4","第2集\u0001file:/two.mp4")).putExtra("startPaused",true).putExtra("title","凡人修仙传")
            val c=Robolectric.buildActivity(PlayerActivity::class.java,intent).setup()
            try {
                val a=c.get(); val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                capture(root,if(land) "player-quality-landscape" else "player-quality-portrait",if(land) 1702 else 720,if(land) 786 else 1600)
                listOf(R.id.btnQuality,R.id.btnFull,R.id.btnEp,R.id.btnSpeed).forEach { id ->
                    val v=a.findViewById<View>(id); assertTrue(v.isShown); assertTrue(v.right<= (v.parent as View).width)
                }
                a.findViewById<View>(R.id.btnQuality).performClick()
                val dialog=ShadowDialog.getLatestDialog()
                capture(dialog.findViewById(android.R.id.content),if(land) "quality-sheet-landscape" else "quality-sheet-portrait",660,400)
                dialog.dismiss()
            } finally { c.pause().stop().destroy() }
        }
    }
}
