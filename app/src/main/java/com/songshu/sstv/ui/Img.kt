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

/** 极简封面加载器：内存 LruCache + 采样解码，不引第三方库 */
object Img {

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
        if (url.isNullOrBlank()) {
            iv.tag = null
            iv.setImageDrawable(null)
            return
        }
        iv.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }
        iv.setImageDrawable(null)
        Bg.img.execute {
            val bmp = runCatching { fetch(url) }.getOrNull()
            if (bmp != null) {
                runCatching { cache.put(url, bmp) }
            }
            iv.post {
                if (iv.tag == url && bmp != null) iv.setImageBitmap(bmp)
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", VodApi.UA)
        val raw = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        if (raw.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 360 && bounds.outHeight / (sample * 2) >= 360) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        if (bmp == null && sample > 1) {
            bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options())
        }
        return bmp
    }
}
