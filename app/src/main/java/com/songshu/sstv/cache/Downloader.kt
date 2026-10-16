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
 * 传了 [CancelFlag] 就能中途取消（取消同样会把 .part 清掉）。
 */
object Downloader {

    /**
     * 这一集是不是已经落在本地了 —— 给「选要缓存哪几集」的面板标状态用。
     *
     * 判据跟 download() 的落盘规则对齐：文件名 = md5(这一集的地址) + 扩展名。
     * 只认下完的正式文件，`.part` / `.segs` 这些中间产物不算（下到一半的别当已有）。
     */
    fun localFile(ctx: Context, url: String): File? {
        if (url.isBlank()) return null
        val dir = File(ctx.filesDir, "downloads")
        if (!dir.isDirectory) return null
        val key = md5(url)
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("$key.") &&
                    !it.name.endsWith(".part") && it.length() > 1024
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
        onProgress: (DlReport) -> Unit = {}
    ): File? {
        val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
        val key = md5(url)
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
            onProgress(DlReport(100, target.length(), target.length(), 0))
            return target
        }
        if (flag?.cancelled == true) return null

        val meter = Meter()
        val report: (Int, Long, Long) -> Unit = { p, b, t ->
            onProgress(DlReport(p, b, t, meter.tick(b)))
        }

        val tmp = File(dir, "$key.part")
        if (tmp.exists()) tmp.delete()

        val ok = try {
            if (isHls) hls(real, tmp, flag, report) else plain(real, tmp, flag, report)
        } catch (e: Exception) {
            false
        }

        if (flag?.cancelled == true) {
            tmp.delete()
            return null
        }
        if (!ok || !tmp.exists() || tmp.length() < 1024) {
            tmp.delete()
            return null
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            return runCatching { tmp.copyTo(target, overwrite = true); tmp.delete(); target }
                .getOrNull()
        }
        DlStore.add(ctx, DlItem(url, title, sub, target.absolutePath, target.length(), System.currentTimeMillis(), pic))
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

        val base = url.substringBeforeLast('/', "")
        val segs = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segs.isEmpty()) return false

        val segDir = File(out.parentFile, out.nameWithoutExtension + ".segs")
        if (segDir.exists()) segDir.deleteRecursively()
        segDir.mkdirs()

        val total = segs.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(4)
        val jobs = ArrayList<java.util.concurrent.Future<Boolean>>(total)
        for (i in 0 until total) {
            val idx = i
            val segUrl = resolve(base, segs[i])
            jobs.add(pool.submit<Boolean> {
                if (flag?.cancelled == true) return@submit false
                val f = File(segDir, "%06d.ts".format(idx))
                val ok = fetchTo(segUrl, f, flag)
                val d = done.incrementAndGet()
                val got = bytes.addAndGet(f.length())
                report((d * 100 / total).coerceIn(0, 99), got, 0)
                ok
            })
        }
        pool.shutdown()

        var allOk = true
        for (j in jobs) {
            if (!runCatching { j.get(180, TimeUnit.SECONDS) }.getOrDefault(false)) allOk = false
            if (flag?.cancelled == true) break
        }
        if (flag?.cancelled == true) {
            pool.shutdownNow()
            segDir.deleteRecursively()
            return false
        }
        if (!allOk) {
            segDir.deleteRecursively()
            return false
        }

        var written = 0L
        FileOutputStream(out).use { fos ->
            for (i in 0 until total) {
                if (flag?.cancelled == true) {
                    segDir.deleteRecursively()
                    return false
                }
                val f = File(segDir, "%06d.ts".format(i))
                if (!f.exists() || f.length() == 0L) {
                    segDir.deleteRecursively()
                    return false
                }
                f.inputStream().use { it.copyTo(fos, 64 * 1024) }
                written += f.length()
            }
        }
        segDir.deleteRecursively()
        report(100, written, written)
        return written > 1024
    }

    // ---------------- 直链 ----------------

    private fun plain(
        url: String,
        out: File,
        flag: CancelFlag?,
        report: (Int, Long, Long) -> Unit
    ): Boolean {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code >= 400) return false
            val totalLen = conn.contentLengthLong
            conn.inputStream.use { ins ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastP = -1
                    var lastT = 0L
                    while (true) {
                        if (flag?.cancelled == true) return false
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        got += n
                        val p = if (totalLen > 0) (got * 100 / totalLen).toInt().coerceIn(0, 99) else 0
                        val now = System.currentTimeMillis()
                        // 百分比变了要报，长时间没变也要报（速度条得动起来）
                        if (p != lastP || now - lastT >= 400) {
                            lastP = p
                            lastT = now
                            report(p, got, if (totalLen > 0) totalLen else 0)
                        }
                    }
                }
            }
            report(100, out.length(), if (totalLen > 0) totalLen else out.length())
        } finally {
            conn.disconnect()
        }
        return true
    }

    // ---------------- 工具 ----------------

    private fun fetchTo(url: String, f: File, flag: CancelFlag?): Boolean {
        repeat(3) {
            if (flag?.cancelled == true) return false
            try {
                val conn = open(url)
                try {
                    if (conn.responseCode >= 400) return@repeat
                    conn.inputStream.use { ins ->
                        FileOutputStream(f).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (flag?.cancelled == true) return false
                                val n = ins.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    if (f.length() > 0) return true
                } finally {
                    conn.disconnect()
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

        fun tick(bytes: Long): Long {
            val now = System.currentTimeMillis()
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
