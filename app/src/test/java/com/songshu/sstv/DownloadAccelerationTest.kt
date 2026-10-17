package com.songshu.sstv

import android.app.Application
import com.songshu.sstv.cache.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class DownloadAccelerationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun server(data: ByteArray, ranges: AtomicInteger, broken: Boolean = false, gate: CountDownLatch? = null,
        concurrent: AtomicBoolean? = null, throttle: Boolean = false): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setHeader("Accept-Ranges","bytes").setHeader("ETag","\"fixed-version\"")
                val range = request.getHeader("Range")
                if (range == null || broken) {
                    if(range != null) ranges.incrementAndGet()
                    return response.setBody(Buffer().write(data))
                }
                ranges.incrementAndGet()
                assertEquals("identity",request.getHeader("Accept-Encoding"))
                assertEquals("\"fixed-version\"",request.getHeader("If-Range"))
                gate?.countDown(); if(gate != null && !gate.await(4,TimeUnit.SECONDS)) concurrent?.set(false)
                val (start,end) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                response.setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data.copyOfRange(start,end+1)))
                if (throttle) response.throttleBody(64*1024,100,TimeUnit.MILLISECONDS)
                return response
            }
        }; start()
    }
    @Test fun largeDirectFileUsesFourConcurrentValidatedRangesWithoutChangingBytes() {
        val data = ByteArray(5*1024*1024+37) { (it%251).toByte() }
        val requests = AtomicInteger(); val concurrent = AtomicBoolean(true)
        val server = server(data,requests,gate=CountDownLatch(4),concurrent=concurrent)
        try {
            val file = Downloader.download(app,"分段下载","正片",server.url("/video.mp4").toString())
            assertNotNull(file); assertArrayEquals(data,file!!.readBytes()); assertEquals(4,requests.get()); assertTrue(concurrent.get())
            assertTrue(file.parentFile!!.listFiles().orEmpty().none { it.name.contains(".ranges-") })
        } finally { server.shutdown() }
    }
    @Test fun serverIgnoringRangeFallsBackToOneWholeFileWithoutCorruption() {
        val data = ByteArray(5*1024*1024+13) { (it%127).toByte() }
        val requests = AtomicInteger(); val server = server(data,requests,broken=true)
        try {
            val file = Downloader.download(app,"回退下载","正片",server.url("/fallback.mp4").toString())
            assertNotNull(file); assertTrue(requests.get()>0); assertArrayEquals(data,file!!.readBytes())
        } finally { server.shutdown() }
    }
    @Test fun cancellingParallelDownloadNeverPublishesPartialContent() {
        val data = ByteArray(5*1024*1024) { (it%113).toByte() }
        val requests = AtomicInteger(); val server = server(data,requests,throttle=true)
        val flag = CancelFlag(); val url = server.url("/cancel.mp4").toString()
        try {
            val file = Downloader.download(app,"取消分段","正片",url,flag=flag) { if(it.bytes>0) flag.cancel() }
            assertTrue(flag.cancelled); assertNull(file); assertFalse(DlStore.list(app).any { it.url == url })
        } finally { server.shutdown() }
    }
    @Test fun hlsRequestsEightSegmentsConcurrentlyAndAssemblesInOriginalOrder() {
        val chunks = (0..7).map { i -> ByteArray(2048) { i.toByte() } }
        val gate = CountDownLatch(8); val concurrent = AtomicBoolean(true)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/index.m3u8") return MockResponse().setBody("#EXTM3U\n"+(0..7).joinToString("\n") { "#EXTINF:6,\n$it.ts" }+"\n#EXT-X-ENDLIST\n")
                gate.countDown(); if(!gate.await(4,TimeUnit.SECONDS)) concurrent.set(false)
                val index = request.path!!.substringAfter('/').substringBefore('.').toInt()
                return MockResponse().setBody(Buffer().write(chunks[index]))
            }
        }; server.start()
        try {
            val file = Downloader.download(app,"并发分段","正片",server.url("/index.m3u8").toString())
            assertNotNull(file); assertTrue(concurrent.get()); assertArrayEquals(chunks.reduce { a,b -> a+b },file!!.readBytes())
        } finally { server.shutdown() }
    }
}
