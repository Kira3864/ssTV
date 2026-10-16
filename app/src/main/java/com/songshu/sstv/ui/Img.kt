package com.songshu.sstv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.songshu.sstv.Bg
import com.songshu.sstv.data.VodApi
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference

/** 极简封面加载器：内存 LruCache + 采样解码，不引第三方库 */
object Img {
    private val pending = HashMap<String, MutableList<WeakReference<ImageView>>>()

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun round(iv: ImageView, radiusPx: Float) {
        iv.clipToOutline = true
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    fun load(iv: ImageView, url: String?) {
        iv.animate().cancel()
        iv.alpha = 1f
        if (url.isNullOrBlank()) {
            iv.tag = null
            iv.setImageDrawable(null)
            return
        }
        if (iv.tag == url && iv.drawable != null) return
        iv.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        synchronized(pending) {
            val waiting = pending[url]
            if (waiting != null) {
                if (waiting.none { it.get() === iv }) waiting.add(WeakReference(iv))
                return
            }
            pending[url] = mutableListOf(WeakReference(iv))
        }
        Bg.img.execute {
            val bmp = runCatching { fetch(url) }.getOrNull()
            if (bmp != null) {
                runCatching { cache.put(url, bmp) }
            }
            val waiting = synchronized(pending) { pending.remove(url).orEmpty() }
            waiting.forEach { ref ->
                val target = ref.get() ?: return@forEach
                target.post {
                    if (target.tag == url && bmp != null) {
                        target.setImageBitmap(bmp)
                        if (target.isAttachedToWindow && Motion.enabled(target)) {
                            target.alpha = 0f
                            target.animate().alpha(1f).setDuration(160).start()
                        }
                    }
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", VodApi.UA)
        val raw = try {
            if (conn.responseCode !in 200..299) return null
            // Reject oversized responses before allocating unbounded image buffers.
            conn.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > 8 * 1024 * 1024) return null
                    bytes.write(buffer, 0, n)
                }
                bytes.toByteArray()
            }
        } finally { conn.disconnect() }
        if (raw.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 960) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }
}
