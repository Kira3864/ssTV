package com.songshu.sstv.cache

import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Verified byte ranges are assembled only after every part has completed. */
internal object RangeTransfer {
    fun download(out: File, size: Long, validator: String, flag: CancelFlag?, open: () -> HttpURLConnection,
        report: (Int, Long, Long) -> Unit): Boolean {
        val dir = File(out.parentFile, out.name + ".ranges-" + UUID.randomUUID()).apply { mkdirs() }
        val parts = (0 until 4).map { File(dir,"$it.part") }
        val stopped = AtomicBoolean(false)
        val active = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val pool = Executors.newFixedThreadPool(4)
        val completion = ExecutorCompletionService<Boolean>(pool)
        val progressLock = Any()
        var received = 0L; var lastPercent = -1; var lastReport = 0L
        fun cancelled() = stopped.get() || flag?.cancelled == true || Thread.currentThread().isInterrupted
        try {
            parts.forEachIndexed { i, part ->
                val first = size*i/4
                val last = size*(i+1)/4-1
                completion.submit {
                    var conn: HttpURLConnection? = null
                    try {
                        if (cancelled()) return@submit false
                        val connection = open(); conn = connection; active.add(connection); flag?.watch(connection)
                        if (cancelled()) return@submit false
                        connection.setRequestProperty("Range","bytes=$first-$last")
                        connection.setRequestProperty("If-Range",validator)
                        if (connection.responseCode != 206) return@submit false
                        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE)
                            .matchEntire(connection.getHeaderField("Content-Range").orEmpty().trim()) ?: return@submit false
                        if (match.groupValues.drop(1).map { it.toLongOrNull() } != listOf(first,last,size)) return@submit false
                        val length = last-first+1
                        if (connection.contentLengthLong >= 0 && connection.contentLengthLong != length) return@submit false
                        if (!connection.getHeaderField("Content-Encoding").isNullOrBlank() && connection.getHeaderField("Content-Encoding") != "identity") return@submit false
                        var written = 0L
                        connection.inputStream.use { input -> part.outputStream().buffered(128*1024).use { output ->
                            val buffer = ByteArray(128*1024)
                            while (written < length) {
                                if (cancelled()) return@submit false
                                val n = input.read(buffer,0,minOf(buffer.size.toLong(),length-written).toInt())
                                if (n < 0) return@submit false
                                output.write(buffer,0,n); written += n
                                synchronized(progressLock) {
                                    received += n
                                    val percent = (received*100/size).toInt().coerceIn(0,99)
                                    val now = System.currentTimeMillis()
                                    if (percent != lastPercent || now-lastReport >= 400) { report(percent,received,size); lastPercent=percent; lastReport=now }
                                }
                            }
                        } }
                        written == length && part.length() == length
                    } catch (_: Exception) { false }
                    finally {
                        conn?.let { active.remove(it); flag?.unwatch(it); it.disconnect() }
                        if (stopped.get()) { part.delete(); dir.delete() }
                    }
                }
            }
            repeat(parts.size) {
                var result: java.util.concurrent.Future<Boolean>? = null
                while (result == null) {
                    if (cancelled()) return false
                    result = completion.poll(200,TimeUnit.MILLISECONDS)
                }
                if (!result.get()) return false
            }
            out.outputStream().buffered(256*1024).use { output ->
                parts.forEach { part ->
                    if (cancelled()) return false
                    part.inputStream().use { it.copyTo(output,128*1024) }
                }
            }
            if (out.length() != size) return false
            report(100,size,size)
            return true
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        catch (_: Exception) { return false }
        finally {
            stopped.set(true); active.forEach { runCatching { it.disconnect() } }; pool.shutdownNow()
            runCatching { pool.awaitTermination(2,TimeUnit.SECONDS) }
            parts.forEach { it.delete() }; dir.delete()
        }
    }
}
