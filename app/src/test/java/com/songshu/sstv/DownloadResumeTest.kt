package com.songshu.sstv

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.songshu.sstv.cache.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="zh-rCN-w393dp-h851dp-port-xhdpi")
class DownloadResumeTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("download_tasks",0).edit().clear().commit()
        app.getSharedPreferences("sstv",0).edit().clear().commit()
        DlHub.init(app); TransferBudget.playbackActive=false
    }
    private fun direct(data: ByteArray, starts: MutableList<Long>, fail: AtomicBoolean = AtomicBoolean(false),
        validator: ()->String = { "v1" }, ignoresRange: AtomicBoolean = AtomicBoolean(false)): MockWebServer {
        val s=MockWebServer()
        s.dispatcher=object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val response=MockResponse().setHeader("ETag",validator()).setHeader("Accept-Ranges","bytes")
                val range=r.getHeader("Range")
                if(range!=null && !ignoresRange.get()) {
                    val (a,b)=range.removePrefix("bytes=").split("-").map { it.toInt() }
                    starts.add(a.toLong())
                    return response.setResponseCode(206).setHeader("Content-Range","bytes $a-$b/${data.size}")
                        .setBody(Buffer().write(data.copyOfRange(a,b+1)))
                }
                response.setBody(Buffer().write(data))
                if(fail.getAndSet(false)) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                return response
            }
        }; s.start(); return s
    }
    @After fun clearTasks() {
        DlHub.list().forEach { DlHub.remove(it) }
        RuntimeEnvironment.getApplication().getSharedPreferences("download_tasks",0).edit().clear().commit()
        DlHub.init(RuntimeEnvironment.getApplication())
        TransferBudget.playbackActive=false
    }
    @Test fun pausedDirectDownloadContinuesAtSavedByteAndProducesIdenticalFile() {
        val data=ByteArray(2*1024*1024) { (it%127).toByte() }
        val starts=Collections.synchronizedList(mutableListOf<Long>()); val server=direct(data,starts)
        val url=server.url("/video.mp4").toString(); val flag=CancelFlag()
        try {
            assertNull(Downloader.download(app,"电影","正片",url,flag=flag) { if(it.bytes>=128*1024) flag.pause() })
            assertNull(Downloader.localFile(app,url))
            val file=Downloader.download(app,"电影","正片",url)!!
            assertArrayEquals(data,file.readBytes()); assertTrue(starts.any { it>0 })
        } finally { server.shutdown() }
    }
    @Test fun networkDisconnectPreservesBytesForRetry() {
        val data=ByteArray(2*1024*1024) { (it%251).toByte() }
        val starts=Collections.synchronizedList(mutableListOf<Long>()); val server=direct(data,starts,AtomicBoolean(true))
        val url=server.url("/broken.mp4").toString()
        try {
            assertNull(Downloader.download(app,"断网","正片",url))
            assertNull(Downloader.localFile(app,url))
            val file=Downloader.download(app,"断网","正片",url)!!
            assertArrayEquals(data,file.readBytes()); assertTrue(starts.any { it>0 })
        } finally { server.shutdown() }
    }
    @Test fun pausedParallelRangesResumeTheirOwnOffsets() {
        val data=ByteArray(5*1024*1024+37) { (it%119).toByte() }
        val starts=Collections.synchronizedList(mutableListOf<Long>()); val server=direct(data,starts)
        val url=server.url("/parallel.mp4").toString(); val flag=CancelFlag()
        try {
            assertNull(Downloader.download(app,"分段","正片",url,flag=flag) { if(it.bytes>=128*1024) flag.pause() })
            val previous=starts.size
            val file=Downloader.download(app,"分段","正片",url)!!
            assertArrayEquals(data,file.readBytes())
            val bases=(0..3).map { data.size.toLong()*it/4 }
            assertTrue("At least one range must start after its saved base",starts.drop(previous).any { it !in bases })
        } finally { server.shutdown() }
    }
    @Test fun changedValidatorAndIgnoredRangesRestartSafely() {
        val data=ByteArray(2*1024*1024) { 5 }; val version=AtomicInteger(1)
        val starts=Collections.synchronizedList(mutableListOf<Long>()); val ignores=AtomicBoolean(false)
        val server=direct(data,starts,validator={ "v${version.get()}" },ignoresRange=ignores)
        val url=server.url("/changed.mp4").toString()
        try {
            val first=CancelFlag()
            assertNull(Downloader.download(app,"变更","正片",url,flag=first) { if(it.bytes>0) first.pause() })
            version.incrementAndGet()
            assertArrayEquals(data,Downloader.download(app,"变更","正片",url)!!.readBytes()); assertTrue(starts.isEmpty())
            val second=server.url("/ignore.mp4").toString(); val flag=CancelFlag()
            assertNull(Downloader.download(app,"忽略","正片",second,flag=flag) { if(it.bytes>0) flag.pause() })
            ignores.set(true)
            assertArrayEquals(data,Downloader.download(app,"忽略","正片",second)!!.readBytes())
        } finally { server.shutdown() }
    }
    @Test fun hlsRetryKeepsCompletedSegmentsAndOnlyFetchesMissingOnes() {
        val broken=AtomicBoolean(true); val counts=(0..7).map { AtomicInteger() }
        val chunks=(0..7).map { n -> ByteArray(4096) { n.toByte() } }
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                if(r.path=="/index.m3u8") return MockResponse().setBody("#EXTM3U\n"+(0..7).joinToString("\n") { "#EXTINF:6,\n$it.ts" }+"\n#EXT-X-ENDLIST\n")
                val n=r.path!!.substringAfter('/').substringBefore('.').toInt(); counts[n].incrementAndGet()
                if(n==7 && broken.get()) return MockResponse().setResponseCode(503)
                return MockResponse().setBody(Buffer().write(chunks[n]))
            }
        }; server.start()
        val url=server.url("/index.m3u8").toString()
        try {
            assertNull(Downloader.download(app,"分片","第1集",url))
            val before=counts.map { it.get() }; broken.set(false)
            assertArrayEquals(chunks.reduce { a,b -> a+b },Downloader.download(app,"分片","第1集",url)!!.readBytes())
            (0..6).forEach { assertEquals("Completed segment $it must be reused",before[it],counts[it].get()) }
        } finally { server.shutdown() }
    }
    @Test fun tasksSurviveRestartAsIndividuallyResumableEpisodes() {
        val t=DlHub.create("剧集","",listOf("第1集" to "https://one.mp4","第2集" to "https://two.mp4"),"https://provider","甲","id",true)
        assertEquals(2,DlHub.list().size)
        DlHub.pause(t); t.curPercent=42; t.doneBytes=4096; DlHub.persist()
        DlHub.init(app)
        assertEquals(2,DlHub.list().size); assertTrue(DlHub.list().all { it.state==DlState.PAUSED })
        assertEquals(42,DlHub.list().first().percent)
        assertEquals(4096L,DlHub.list().first().doneBytes)
        assertEquals(1,CacheGroups.rows(emptyList(),true,DlHub.list()).size)
        assertEquals(2,CacheGroups.rows(emptyList(),false,DlHub.list()).size)
    }
    @Test fun seriesFolderOpensPendingEpisodesWithIndividualControlsAndScrollableList() {
        DlHub.create("剧集","",(1..25).map { "第${it}集" to "https://invalid/$it.mp4" },"https://provider","甲","id",true)
        val controller=Robolectric.buildActivity(CacheActivity::class.java).setup()
        try {
            val activity=controller.get(); val list=activity.findViewById<RecyclerView>(R.id.list)
            layout(list); assertEquals(1,list.adapter!!.itemCount)
            list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            val intent=Shadows.shadowOf(activity).nextStartedActivity
            val folder=Robolectric.buildActivity(CacheActivity::class.java,intent).setup()
            try {
                val inner=folder.get().findViewById<RecyclerView>(R.id.list); layout(inner)
                assertEquals(25,inner.adapter!!.itemCount)
                val row=inner.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals("暂停",row.findViewById<TextView>(R.id.btnCancel).text.toString())
                row.findViewById<View>(R.id.btnCancel).performClick()
                assertEquals(DlState.PAUSED,DlHub.list().first().state)
                assertTrue(row.findViewById<View>(R.id.btnDel).isShown)
                inner.scrollToPosition(24); layout(inner)
                assertNotNull(inner.findViewHolderForAdapterPosition(24))
            } finally { folder.pause().stop().destroy() }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun pausedRunnerCanResumeAfterTaskStoreReloadAndDeletingRemovesItsData() {
        val server=MockWebServer(); val data=ByteArray(2*1024*1024) { (it%101).toByte() }
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val response=MockResponse().setHeader("ETag","runner-v1")
                val range=r.getHeader("Range")
                if(range==null) return response.setBody(Buffer().write(data)).throttleBody(64*1024,30,TimeUnit.MILLISECONDS)
                val (a,b)=range.removePrefix("bytes=").split("-").map { it.toInt() }
                return response.setResponseCode(206).setHeader("Content-Range","bytes $a-$b/${data.size}").setBody(Buffer().write(data.copyOfRange(a,b+1)))
            }
        }; server.start()
        val url=server.url("/runner.mp4").toString()
        try {
            val t=DlHub.create("剧集","",listOf("第1集" to url),"https://provider","甲","id",true)
            val worker=Thread { DlRunner.run(app,t) }; worker.start()
            val limit=System.currentTimeMillis()+10000
            while(t.doneBytes==0L && System.currentTimeMillis()<limit) Thread.sleep(10)
            assertTrue(t.doneBytes>0); DlHub.pause(t); worker.join(10000); assertFalse(worker.isAlive)
            assertEquals(DlState.PAUSED,t.state)
            DlHub.init(app); val restored=DlHub.list().single()
            assertEquals(DlState.PAUSED,restored.state); DlHub.resume(app,restored)
            val deadline=System.currentTimeMillis()+10000
            while((restored.isRunning || restored.worker.get()) && System.currentTimeMillis()<deadline) Thread.sleep(10)
            assertEquals(DlState.OK,restored.state)
            assertArrayEquals(data,Downloader.localFile(app,url,"https://provider")!!.readBytes())
            DlHub.remove(restored); DlHub.init(app); assertTrue(DlHub.list().isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun resumingFirstQueuedEpisodeDoesNotLoseTheRestOfTheBatch() {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest)=MockResponse().setBody(Buffer().write(ByteArray(4096) { 7 }))
        }; server.start()
        try {
            val first=DlHub.create("队列","",listOf("第1集" to server.url("/1.mp4").toString(),"第2集" to server.url("/2.mp4").toString()))
            DlHub.pause(first); DlHub.resume(app,first)
            val limit=System.currentTimeMillis()+10000
            while(DlHub.list().any { it.state!=DlState.OK } && System.currentTimeMillis()<limit) Thread.sleep(10)
            assertTrue(DlHub.list().all { it.state==DlState.OK })
            assertEquals(2,DlStore.list(app).size)
        } finally { server.shutdown() }
    }

    private fun layout(v: View) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        v.measure(View.MeasureSpec.makeMeasureSpec(786,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.EXACTLY))
        v.layout(0,0,786,1400)
    }
}
