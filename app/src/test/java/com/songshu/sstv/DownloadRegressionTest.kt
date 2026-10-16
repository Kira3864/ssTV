package com.songshu.sstv

import android.app.Application
import com.songshu.sstv.cache.DlItem
import com.songshu.sstv.cache.DlStore
import com.songshu.sstv.cache.Downloader
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadRegressionTest {
    @Test fun nestedHlsRelativeSegmentsAreDownloadedInPlaylistOrder() {
        val app = RuntimeEnvironment.getApplication()
        val server = MockWebServer()
        val first = ByteArray(2048) { 1 }
        val second = ByteArray(2048) { 2 }
        val responses = mapOf(
            "/media/season/index.m3u8" to "#EXTM3U\n#EXTINF:6,\nfirst.ts\n#EXTINF:6,\nsecond.ts\n#EXT-X-ENDLIST\n".toByteArray(),
            "/media/season/first.ts" to first,
            "/media/season/second.ts" to second)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = responses[request.path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
        try {
            val url = server.url("/media/season/index.m3u8").toString()
            val file = Downloader.download(app, "测试影片", "第一集", url)
            assertNotNull("Relative segment paths must keep the playlist directory", file)
            assertArrayEquals(first + second, file!!.readBytes())
            assertTrue(DlStore.list(app).any { it.url == url && it.path == file.absolutePath })
        } finally { server.shutdown() }
    }

    @Test fun concurrentDownloadCompletionsKeepEveryIndexEntry() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("sstv", 0).edit().clear().commit()
        val pool = Executors.newFixedThreadPool(6)
        val start = java.util.concurrent.CountDownLatch(1)
        repeat(30) { i ->
            pool.submit {
                start.await()
                DlStore.add(app, DlItem("url-$i", "影片", "$i", "/$i.ts", 2048, i.toLong()))
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(30, DlStore.list(app).size)
    }
}
