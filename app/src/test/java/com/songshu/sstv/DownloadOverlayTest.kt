package com.songshu.sstv

import android.app.Application
import com.songshu.sstv.cache.DlHub
import com.songshu.sstv.ui.DlOverlay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class DownloadOverlayTest {
    @Test fun creatingOrResumingDownloadsWithoutOverlayPermissionDoesNotPromptOrOpenSettings() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("download_tasks",0).edit().clear().commit(); DlHub.init(app)
        DlOverlay.install(app)
        val controller=Robolectric.buildActivity(CacheActivity::class.java).setup()
        try {
            assertFalse(DlOverlay.canDraw(app))
            val previousDialog=ShadowDialog.getLatestDialog()
            repeat(3) { n ->
                DlHub.create("剧集","",listOf("第${n}集" to "https://example.invalid/$n.mp4"))
                DlHub.onTaskCreated?.invoke()
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertSame(previousDialog,ShadowDialog.getLatestDialog())
                assertNull(Shadows.shadowOf(controller.get()).nextStartedActivity)
            }
            assertEquals(3,DlHub.list().size)
        } finally {
            controller.pause().stop().destroy()
            DlHub.list().forEach { DlHub.remove(it) }; DlHub.onTaskCreated=null
        }
    }
}
