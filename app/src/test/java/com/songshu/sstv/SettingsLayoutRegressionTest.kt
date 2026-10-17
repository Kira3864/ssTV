package com.songshu.sstv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import com.songshu.sstv.ui.SourceAction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val a = Source("片源甲", "https://a.example/api.php/provide/vod")
    private val b = Source("片源乙", "https://b.example/api.php/provide/vod")
    @Before fun reset() {
        app.getSharedPreferences("download_tasks",0).edit().clear().commit()
        com.songshu.sstv.cache.DlHub.init(app)
        app.getSharedPreferences("sstv", 0).edit().clear().commit()
        app.getSharedPreferences("sstv_player", 0).edit().clear().commit()
        ThemeStore.select(app, ThemeStore.styles.first { it.id == "paper" }); ThemeStore.setReducedMotion(app, true)
        SourceStore.sources(app); SourceStore.save(app, listOf(a, b)); SourceStore.setCurrent(app, a)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View, width: Int = 786, height: Int = 1702) {
        views(view).forEach { it.forceLayout() }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun capture(view: View, name: String, width: Int = 786, height: Int = 1702) {
        shadowOf(Looper.getMainLooper()).idle(); layout(view, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/reports/ui").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun settingsSwitchesPersistAndTitlesAreBoldWithoutAnInlineSourceList() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get(); val root = activity.findViewById<View>(R.id.settingsContent)
            assertNull(activity.findViewById<View>(R.id.sourceList))
            val labels = setOf("保留上次播放内容", "边看边缓存", "主题风格", "减少动画", "片源管理", "NSFW", "缓存悬浮窗", "清理在线播放缓存", "已缓存视频", "当前版本")
            val titles = views(root).filterIsInstance<TextView>().filter { it.text.toString() in labels }
            assertEquals(labels.size, titles.size)
            titles.forEach { assertTrue("${it.text} should be bold", it.typeface.isBold) }
            activity.findViewById<SwitchCompat>(R.id.swRestorePlayback).performClick()
            assertFalse(PlaybackPreferences.restoreLast(activity))
            activity.findViewById<SwitchCompat>(R.id.swReduceMotion).performClick()
            assertFalse(ThemeStore.reducedMotion(activity))
            activity.findViewById<SwitchCompat>(R.id.swAutoCache).performClick()
            assertFalse(SourceStore.autoCache(activity))
            activity.findViewById<SwitchCompat>(R.id.swFilterAdult).performClick()
            assertFalse(SourceStore.filterAdult(activity))
            activity.findViewById<View>(R.id.btnDownloads).performClick()
            assertEquals(CacheActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun sourceManagerFiltersSelectsAndEditsTheCurrentSourceInItsPopup() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get(); activity.findViewById<View>(R.id.btnManageSources).performClick()
            val dialog = ShadowDialog.getLatestDialog(); val root = dialog.findViewById<View>(android.R.id.content)
            layout(root, 738, 1360)
            dialog.findViewById<EditText>(R.id.sourceSearch).setText("乙")
            layout(root, 738, 1360)
            val list = dialog.findViewById<RecyclerView>(R.id.sourceList)
            assertEquals(1, list.adapter?.itemCount)
            list.getChildAt(0).performClick()
            assertEquals(b.api, SourceStore.current(activity).api)
            layout(root, 738, 1360)
            views(list).filterIsInstance<TextView>().single { it.contentDescription == "管理片源乙" }.performClick()
            ShadowPopupMenu.getLatestPopupMenu().menu.performIdentifierAction(SourceAction.EDIT.ordinal, 0)
            val editor = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            val edits = views(requireNotNull(editor.findViewById<View>(android.R.id.content))).filterIsInstance<EditText>()
            edits[0].setText("片源乙新版"); edits[1].setText("https://edited.example/api.php/provide/vod")
            editor.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("片源乙新版", SourceStore.current(activity).name)
            assertEquals("https://edited.example/api.php/provide/vod", SourceStore.current(activity).api)
            assertTrue(dialog.isShowing)
            assertEquals(1, list.adapter?.itemCount)
            dialog.dismiss()
            assertEquals("2 个", activity.findViewById<TextView>(R.id.sourceSummary).text.toString())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun renderAllSettingsThemesDialogsAndTheBoundedCacheEmptyState() {
        ThemeStore.styles.forEach { style ->
            ThemeStore.select(app, style)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            try {
                val activity = controller.get(); val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                capture(root, "settings-${style.id}.png")
                val scroll = activity.findViewById<ScrollView>(R.id.settingsScroll)
                assertTrue("Settings should fit within two screens", scroll.getChildAt(0).height < scroll.height * 2)
                if (style.id == "paper") {
                    scroll.fullScroll(View.FOCUS_DOWN); capture(root, "settings-paper-bottom.png")
                    activity.findViewById<View>(R.id.btnTheme).performClick()
                    var dialog = ShadowDialog.getLatestDialog()
                    capture(dialog.findViewById(android.R.id.content), "settings-themes.png", 722, 756)
                    dialog.dismiss()
                    activity.findViewById<View>(R.id.btnManageSources).performClick()
                    dialog = ShadowDialog.getLatestDialog()
                    capture(dialog.findViewById(android.R.id.content), "settings-sources.png", 738, 1360)
                    dialog.dismiss()
                }
            } finally { controller.pause().stop().destroy() }
        }
        ThemeStore.select(app, ThemeStore.styles.first { it.id == "paper" })
        val cache = Robolectric.buildActivity(CacheActivity::class.java).setup()
        try {
            val root = cache.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            capture(root, "cache-empty.png")
            val hint = cache.get().findViewById<TextView>(R.id.hint)
            assertEquals("暂无缓存", hint.text.toString())
            val bounds = android.graphics.Rect(); assertTrue(hint.getGlobalVisibleRect(bounds))
            assertTrue(bounds.left >= 64); assertTrue(bounds.right <= root.width - 64)
            assertEquals(root.width / 2, bounds.centerX())
        } finally { cache.pause().stop().destroy() }
    }
}
