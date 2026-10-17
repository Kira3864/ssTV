package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.songshu.sstv.cast.CastDevice
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CastHandoffTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Casting handoff did not settle")
    }
    @Test fun disconnectAfterRemoteEpisodeChangePreparesCurrentEpisodeLocallyPaused() {
        val server = MockWebServer()
        val loads = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/control") {
                    if (request.getHeader("SOAPAction")?.contains("#SetAVTransportURI") == true) loads.incrementAndGet()
                    return MockResponse().setBody("<ok/>")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val app = RuntimeEnvironment.getApplication()
        val first = server.url("/first.mp4").toString()
        val second = server.url("/second.mp4").toString()
        val launch = Intent(app, PlayerActivity::class.java).putExtra("title", "测试影片").putExtra("startPaused", true)
            .putStringArrayListExtra("eps", arrayListOf("第一集\u0001$first", "第二集\u0001$second"))
        val controller = Robolectric.buildActivity(PlayerActivity::class.java, launch).setup()
        val activity = controller.get()
        val player = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer
        try {
            await { player.currentMediaItem != null }
            val device = CastDevice("测试电视", "", server.url("/control").toString(), "urn:schemas-upnp-org:service:AVTransport:1")
            PlayerActivity::class.java.getDeclaredMethod("castMedia", CastDevice::class.java, java.lang.Long.TYPE)
                .apply { isAccessible = true }.invoke(activity, device, 0L)
            await { loads.get() >= 1 }
            activity.findViewById<View>(R.id.btnNext).performClick()
            await { loads.get() >= 2 }
            PlayerActivity::class.java.getDeclaredMethod("disconnectCast", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(activity, true)
            assertEquals(second, player.currentMediaItem!!.localConfiguration!!.uri.toString())
            assertFalse(player.playWhenReady)
        } finally { controller.pause().stop().destroy(); server.shutdown() }
    }
}
