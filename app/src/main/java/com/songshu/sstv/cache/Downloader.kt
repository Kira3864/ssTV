package com.songshu.sstv.cache

import android.content.Context
import com.songshu.sstv.data.Mc
import com.songshu.sstv.data.PlayResolver
import com.songshu.sstv.data.VodApi
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次进度上报：百分比 + 已下字节 + 总字节（未知为 0）+ 速度（B/s） */
class DlReport(
    val percent: Int,
    val bytes: Long,
    val total: Long,
    val speed: Long
)

/**
 * 整集缓存器。
 * - HLS（.m3u8）：拉取播放列表 → 分段并发下载 → 按顺序拼成一个 .ts 文件
 * - 直链（mp4 等）：直接落盘
 * 落盘一律先写 .part，校验通过才改名，中途失败不会留下半个坏文件；
 * 暂停或网络中断保留已校验的数据，显式删除才清理临时文件。
 */
object Downloader {

    /**
     * 这一集是不是已经落在本地了 —— 给「选要缓存哪几集」的面板标状态用。
     *
     * 判据跟 download() 的落盘规则对齐：文件名 = md5(这一集的地址) + 扩展名。
     * 只认下完的正式文件，`.part` / `.segs` 这些中间产物不算（下到一半的别当已有）。
     */
    fun localFile(ctx: Context, url: String, api: String? = null): File? {
        if (url.isBlank()) return null
        DlStore.list(ctx).firstOrNull { it.url == url && (api == null || it.api == api) && File(it.path).isFile }?.let { return File(it.path) }
        if (api != null) return null
        val dir = File(ctx.filesDir, "downloads")
        if (!dir.isDirectory) return null
        val key = md5(url)
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$key.") &&
                    !it.name.contains(".part") && it.length() > 1024
        }
    }

    /** 返回本地文件；失败或取消返回 null（取消请看 flag.cancelled） */
    fun download(
        ctx: Context,
        title: String,
        sub: String,
        url: String,
        pic: String = "",
        flag: CancelFlag? = null,
        api: String = "", sourceName: String = "", vid: String = "", series: Boolean = false,
        onProgress: (DlReport) -> Unit = {}
    ): File? {
        val root = File(ctx.filesDir, "downloads")
        val dir = (if (series) File(root, md5(api + "|" + vid.ifBlank { title })) else root).apply { mkdirs() }
        val key = md5(if (api.isBlank()) url else api + "|" + url)
        // 资源站给的常是「播放页」，先解析成真正的 m3u8 / 直链
        val real = if (PlayResolver.isPage(url)) {
            runCatching { PlayResolver.resolve(url) }.getOrDefault(url)
        } else url
        val isHls = Mc.isHls(real)
        val ext = if (isHls) "ts" else {
            val e = real.substringBefore('?').substringAfterLast('.', "mp4").lowercase()
            if (e.length in 2..5 && e.all { it.isLetterOrDigit() }) e else "mp4"
        }
        val target = File(dir, "$key.$ext")
        if (target.exists() && target.length() > 1024) {
            DlStore.add(ctx, DlItem(url, title, sub, target.absolutePath, target.length(), System.currentTimeMillis(), pic, api, sourceName, vid, series))
            onProgress(DlReport(100, target.length(), target.length(), 0))
            return target
        }
        if (flag?.cancelled == true) return null

        val meter = Meter()
        val report: (Int, Long, Long) -> Unit = { p, b, t ->
            onProgress(DlReport(p, b, t, meter.tick(b)))
        }

        val tmp = File(dir, "$key.part")

        val ok = try {
            if (isHls) hls(real, tmp, flag, report) else plain(real, tmp, flag, report)
        } catch (e: Exception) {
            false
        }

        if (flag?.cancelled == true) {
            if (!flag.keepPartial) discard(ctx,url,api,vid,title,series)
            return null
        }
        if (!ok || !tmp.exists() || tmp.length() < 1024) {
            return null
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            val copied = runCatching { tmp.copyTo(target, overwrite = true); tmp.delete(); target }.getOrNull()
            if (copied == null) return null
        }
        discard(ctx,url,api,vid,title,series)
        DlStore.add(ctx, DlItem(url, title, sub, target.absolutePath, target.length(), System.currentTimeMillis(), pic, api, sourceName, vid, series))
        return target
    }

    // ---------------- HLS ----------------

    private fun hls(
        url: String,
        out: File,
        flag: CancelFlag?,
        report: (Int, Long, Long) -> Unit
    ): Boolean {
        val text = VodApi.get(url, 20000)

        // 主播放列表：挑第一条码流
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines().map { it.trim() }
            val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            if (i >= 0) {
                val sub = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (sub != null) return hls(resolve(url, sub), out, flag, report)
            }
        }

        val segs = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segs.isEmpty()) return false

        val segDir = File(out.parentFile, out.nameWithoutExtension + ".segs")
        segDir.mkdirs()
        val manifest=File(segDir,"playlist")
        if (!manifest.exists() || manifest.readText() != text) {
            segDir.deleteRecursively(); segDir.mkdirs(); manifest.writeText(text)
        }

        val total = segs.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val progressLock = Any()
        val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val pool = Executors.newFixedThreadPool(minOf(TransferBudget.workers, total))
        val jobs = ArrayList<java.util.concurrent.Future<Boolean>>(total)
        for (i in 0 until total) {
            val idx = i
            val segUrl = resolve(url, segs[i])
            jobs.add(pool.submit<Boolean> {
                if (flag?.cancelled == true) return@submit false
                val f = File(segDir, "%06d.ts".format(idx))
                val partial=File(segDir,"%06d.part".format(idx))
                val ok = if (f.isFile && f.length()>0) true else {
                    val fetched=TransferBudget.transfer(flag) { fetchTo(segUrl,partial,flag,connections) }
                    fetched && partial.renameTo(f)
                }
                synchronized(progressLock) {
                    val d = done.incrementAndGet()
                    val got = bytes.addAndGet(f.length())
                    report((d * 100 / total).coerceIn(0, 99), got, 0)
                }
                ok
            })
        }
        pool.shutdown()

        var allOk = true
        for (j in jobs) {
            if (!runCatching { j.get(180, TimeUnit.SECONDS) }.getOrDefault(false)) { allOk = false; break }
            if (flag?.cancelled == true) break
        }
        if (!allOk || flag?.cancelled == true || Thread.currentThread().isInterrupted) {
            pool.shutdownNow()
            connections.forEach { runCatching { it.disconnect() } }
            val stopped = runCatching { pool.awaitTermination(30, TimeUnit.SECONDS) }.getOrDefault(false)
            if (stopped && flag?.cancelled == true && !flag.keepPartial) segDir.deleteRecursively()
            return false
        }

        var written = 0L
        FileOutputStream(out).buffered(256*1024).use { fos ->
            for (i in 0 until total) {
                if (flag?.cancelled == true) {
                    return false
                }
                val f = File(segDir, "%06d.ts".format(i))
                if (!f.exists() || f.length() == 0L) {
                    return false
                }
                f.inputStream().use { it.copyTo(fos, 128 * 1024) }
                written += f.length()
            }
        }
        segDir.deleteRecursively()
        report(100, written, written)
        return written > 1024
    }

    // ---------------- 直链 ----------------

    private fun plain(url: String, out: File, flag: CancelFlag?, report: (Int, Long, Long) -> Unit): Boolean {
        val conn = open(url)
        flag?.watch(conn)
        try {
            if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
            if (conn.responseCode != 200) return false
            val size = conn.contentLengthLong
            val validator = conn.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: conn.getHeaderField("Last-Modified")
            val ranges = conn.getHeaderField("Accept-Ranges").orEmpty().equals("bytes",true)
            val encoding = conn.getHeaderField("Content-Encoding").orEmpty()
            if (size >= 4*1024*1024 && ranges && !validator.isNullOrBlank() && (encoding.isBlank() || encoding == "identity")) {
                conn.disconnect()
                if (RangeTransfer.download(out,size,validator,flag,{ open(url) },report,url)) return true
                // Preserve verified ranges after a network interruption; a retry resumes them.
                if (File(out.parentFile,out.name+".ranges").listFiles().orEmpty().any { it.extension == "part" && it.length()>0 }) return false
                if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
                val fallback = open(url); flag?.watch(fallback)
                try { return copyResponse(fallback,out,flag,report) } finally { flag?.unwatch(fallback); fallback.disconnect() }
            }
            return resumeResponse(url,conn,out,flag,report)
        } finally { flag?.unwatch(conn); conn.disconnect() }
    }

    private fun resumeResponse(url: String, initial: HttpURLConnection, out: File, flag: CancelFlag?, report: (Int,Long,Long)->Unit): Boolean {
        val total=initial.contentLengthLong
        val validator=initial.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: initial.getHeaderField("Last-Modified")
        val meta=File(out.parentFile,out.name+".resume")
        val signature="$url\n$total\n$validator"
        val offset=out.length()
        if (offset>0 && total>0 && offset<=total && !validator.isNullOrBlank() && meta.exists() && meta.readText()==signature) {
            initial.disconnect()
            val conn=open(url); flag?.watch(conn)
            try {
                conn.setRequestProperty("Range","bytes=$offset-${total-1}"); conn.setRequestProperty("If-Range",validator)
                if (offset==total) return true
                if (conn.responseCode==206 && conn.getHeaderField("Content-Range")=="bytes $offset-${total-1}/$total") {
                    var got=offset
                    conn.inputStream.use { input -> FileOutputStream(out,true).buffered(256*1024).use { output ->
                        val buf=ByteArray(128*1024)
                        while (got<total) {
                            if (flag?.cancelled==true || Thread.currentThread().isInterrupted) return false
                            val n=input.read(buf,0,minOf(buf.size.toLong(),total-got).toInt()); if(n<0) return false
                            output.write(buf,0,n); got+=n; report((got*100/total).toInt().coerceAtMost(99),got,total)
                        }
                    } }
                    report(100,total,total); return out.length()==total
                }
            } finally { flag?.unwatch(conn); conn.disconnect() }
            val fresh=open(url); flag?.watch(fresh)
            try {
                if(fresh.responseCode!=200) return false
                val v=fresh.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: fresh.getHeaderField("Last-Modified")
                meta.writeText("$url\n${fresh.contentLengthLong}\n$v")
                return copyResponse(fresh,out,flag,report)
            } finally { flag?.unwatch(fresh); fresh.disconnect() }
        }
        meta.writeText(signature)
        return copyResponse(initial,out,flag,report)
    }

    fun discard(ctx: Context, url: String, api: String, vid: String, title: String, series: Boolean) {
        val root=File(ctx.filesDir,"downloads")
        val dir=if(series) File(root,md5(api+"|"+vid.ifBlank { title })) else root
        val key=md5(if(api.isBlank()) url else api+"|"+url)
        listOf("$key.part","$key.part.resume","$key.part.ranges","$key.segs").forEach { File(dir,it).deleteRecursively() }
    }

    private fun copyResponse(conn: HttpURLConnection, out: File, flag: CancelFlag?, report: (Int, Long, Long) -> Unit): Boolean {
        if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
        if (conn.responseCode != 200) return false
        val total = conn.contentLengthLong
        var got = 0L; var lastPercent = -1; var lastReport = 0L
        conn.inputStream.use { input -> out.outputStream().buffered(256*1024).use { output ->
            val buffer = ByteArray(128*1024)
            while (true) {
                if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer,0,count); got += count
                val percent = if (total > 0) (got*100/total).toInt().coerceIn(0,99) else 0
                val now = System.currentTimeMillis()
                if (percent != lastPercent || now-lastReport >= 400) { report(percent,got,total.coerceAtLeast(0)); lastPercent=percent; lastReport=now }
            }
        } }
        if (total > 0 && out.length() != total) return false
        report(100,out.length(),out.length()); return true
    }

    // ---------------- 工具 ----------------

    private fun fetchTo(url: String, f: File, flag: CancelFlag?, active: MutableSet<HttpURLConnection>): Boolean {
        repeat(3) {
            if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
            try {
                val conn = open(url); flag?.watch(conn); active.add(conn)
                try {
                    if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
                    if (conn.responseCode >= 400) return@repeat
                    val expected = conn.contentLengthLong
                    conn.inputStream.use { ins ->
                        FileOutputStream(f).use { fos ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                if (flag?.cancelled == true || Thread.currentThread().isInterrupted) return false
                                val n = ins.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    if (f.length() > 0 && (expected < 0 || f.length() == expected)) return true
                } finally {
                    active.remove(conn); flag?.unwatch(conn); conn.disconnect()
                }
            } catch (e: Exception) {
                // 重试
            }
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        // 路径可能带中文（综艺类常见），先转义
        val safe = VodApi.encodeUrl(url)
        val conn = URL(safe).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", VodApi.UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        runCatching { "${URL(safe).protocol}://${URL(safe).host}/" }
            .getOrNull()?.let { conn.setRequestProperty("Referer", it) }
        return conn
    }

    private fun resolve(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return runCatching { URL(URL(base), rel).toString() }.getOrDefault(rel)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 算瞬时速度：每 600ms 一个窗口，免得数字乱跳 */
    private class Meter {
        private var t0 = System.currentTimeMillis()
        private var b0 = 0L
        private var v = 0L
        private var initialized = false

        @Synchronized fun tick(bytes: Long): Long {
            val now = System.currentTimeMillis()
            if (!initialized) { initialized=true; t0=now; b0=bytes; return 0 }
            val dt = now - t0
            if (dt >= 600) {
                val d = bytes - b0
                if (d >= 0) v = d * 1000 / dt
                t0 = now
                b0 = bytes
            }
            return v
        }
    }
}
