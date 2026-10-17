package com.songshu.sstv

import android.app.Application
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.songshu.sstv.data.Source
import com.songshu.sstv.data.SourceStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppearanceRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("appearance", 0).edit().clear().commit()
        app.getSharedPreferences("sstv", 0).edit().clear().commit()
        app.getSharedPreferences("sstv_player", 0).edit().clear().commit()
    }

    @Test fun freshDefaultsUsePaperAndRestorePlaybackWithoutOverridingSavedChoices() {
        assertEquals("paper", ThemeStore.current(app).id)
        assertTrue(PlaybackPreferences.restoreLast(app))
        ThemeStore.select(app, ThemeStore.styles.first { it.id == "cinema" })
        PlaybackPreferences.setRestoreLast(app, false)
        assertEquals("cinema", ThemeStore.current(app).id)
        assertFalse(PlaybackPreferences.restoreLast(app))
        app.getSharedPreferences("appearance", 0).edit().putString("theme", "removed-theme").commit()
        assertEquals("paper", ThemeStore.current(app).id)
    }

    @Test fun allThemesInflateAllBrowsingLayouts() {
        val layouts = listOf(R.layout.activity_main, R.layout.activity_search,
            R.layout.activity_settings, R.layout.activity_detail, R.layout.activity_history,
            R.layout.activity_cache, R.layout.activity_my, R.layout.activity_player, R.layout.item_vod, R.layout.item_history, R.layout.item_cached,
            R.layout.item_dl_task)
        ThemeStore.styles.forEach { style ->
            val context = ContextThemeWrapper(app, style.resource)
            assertEquals(style.background, context.themeColor(R.attr.appBg))
            assertEquals(style.accent, context.themeColor(R.attr.appAccent))
            layouts.forEach { layout ->
                val view = LayoutInflater.from(context).inflate(layout, null, false)
                // Resolve gradients, themed dimensions, and ripple drawables at draw time too.
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 1920)
                val bitmap = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                bitmap.recycle()
            }
        }
    }

    @Test fun selectedThemeAndMotionPreferencePersistIndependently() {
        ThemeStore.select(app, ThemeStore.styles[2])
        ThemeStore.setReducedMotion(app, true)
        assertEquals("paper", ThemeStore.current(app).id)
        assertTrue(ThemeStore.reducedMotion(app))
        ThemeStore.select(app, ThemeStore.styles[1])
        assertTrue(ThemeStore.reducedMotion(app))
        assertEquals("aurora", ThemeStore.current(app).id)
    }

    @Test fun duplicateSourceNamesDoNotChangeTheSelectedEndpoint() {
        SourceStore.sources(app) // Finish the one-time preset migration first.
        val first = Source("同名接口", "https://one.example/api.php/provide/vod")
        val second = Source("同名接口", "https://two.example/api.php/provide/vod")
        SourceStore.save(app, listOf(first, second))
        SourceStore.setCurrent(app, second)
        assertEquals(second, SourceStore.current(app))
        val edited = second.copy(name = "改名接口", api = "https://three.example/api.php/provide/vod")
        SourceStore.save(app, listOf(first, edited))
        SourceStore.setCurrent(app, edited)
        assertEquals(edited, SourceStore.current(app))
    }

    @Test fun legacySourceNamePreferenceStillResolves() {
        val first = SourceStore.sources(app).first()
        app.getSharedPreferences("sstv", 0).edit().putString("current", first.name).commit()
        assertEquals(first.api, SourceStore.current(app).api)
    }
}
