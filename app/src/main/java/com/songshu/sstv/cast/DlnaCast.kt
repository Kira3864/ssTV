package com.songshu.sstv.cast

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class CastDevice(val name: String, val location: String, val controlUrl: String, val serviceType: String)
data class CastPosition(val position: Long, val duration: Long)

/** DLNA AVTransport over SSDP + SOAP; no vendor account or cloud service. */
object DlnaCast {
    private fun xml(text: String): org.w3c.dom.Document {
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "不支持含 DTD 的设备响应" }
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    fun location(response: String): String? = response.lineSequence().firstOrNull {
        it.substringBefore(':').trim().equals("location", true)
    }?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    fun parseDevice(location: String, description: String): CastDevice? {
        val doc = xml(description)
        val services = doc.getElementsByTagNameNS("*", "service")
        fun Element.text(tag: String) = getElementsByTagNameNS("*", tag).item(0)?.textContent.orEmpty().trim()
        val base = doc.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim().takeUnless { it.isNullOrBlank() } ?: location
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = service.text("serviceType")
            if (!type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) continue
            val control = service.text("controlURL")
            if (control.isBlank()) continue
            val resolved = URL(URL(base), control)
            require(resolved.protocol in listOf("http", "https"))
            val name = doc.getElementsByTagNameNS("*", "friendlyName").item(0)?.textContent?.trim().orEmpty()
            return CastDevice(name.ifBlank { resolved.host }, location, resolved.toString(), type)
        }
        return null
    }

    fun discover(context: Context, found: (CastDevice) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("sstv-cast-discovery")?.apply { setReferenceCounted(false) }
        val descriptions = Executors.newFixedThreadPool(3)
        try {
            lock?.acquire()
            val seen = HashSet<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val destination = InetAddress.getByName("239.255.255.250")
                listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1").forEach { target ->
                    val message = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n").toByteArray()
                    socket.send(DatagramPacket(message, message.size, destination, 1900))
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                    val response = DatagramPacket(ByteArray(8192), 8192)
                    try { socket.receive(response) } catch (_: SocketTimeoutException) { continue }
                    val address = location(String(response.data, 0, response.length)) ?: continue
                    if (seen.size >= 40 || !seen.add(address)) continue
                    descriptions.execute {
                        if (Thread.currentThread().isInterrupted) return@execute
                        runCatching { parseDevice(address, request(address)) }.getOrNull()?.let(found)
                    }
                }
            }
            descriptions.shutdown()
            descriptions.awaitTermination(6, TimeUnit.SECONDS)
        } finally {
            descriptions.shutdownNow()
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun request(url: String, body: String? = null, action: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 4000
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPAction", "\"$action\"")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { it.readBytesLimited(1024 * 1024) } ?: ByteArray(0)
            check(code in 200..299) { "电视拒绝了操作（HTTP $code）" }
            return String(bytes, Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            check(out.size() + n <= limit) { "设备响应过大" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun soap(device: CastDevice, action: String, arguments: Map<String, String> = emptyMap()): String {
        val args = (mapOf("InstanceID" to "0") + arguments).entries.joinToString("") { (key, value) ->
            "<$key>${escape(value)}</$key>"
        }
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${device.serviceType}\">$args</u:$action></s:Body></s:Envelope>"
        val response = request(device.controlUrl, envelope, "${device.serviceType}#$action")
        val doc = xml(response)
        check(doc.getElementsByTagNameNS("*", "Fault").length == 0) {
            doc.getElementsByTagNameNS("*", "errorDescription").item(0)?.textContent ?: "电视不支持此操作"
        }
        return response
    }

    fun load(device: CastDevice, url: String, title: String, start: Long, autoplay: Boolean) {
        require(URL(url).protocol in listOf("http", "https")) { "仅支持在线视频投屏" }
        val mime = if (url.substringBefore('?').endsWith(".m3u8", true)) "application/vnd.apple.mpegurl" else "video/mp4"
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${escape(url)}</res></item></DIDL-Lite>"
        soap(device, "SetAVTransportURI", mapOf("CurrentURI" to url, "CurrentURIMetaData" to metadata))
        soap(device, "Play", mapOf("Speed" to "1"))
        if (start > 0) runCatching { seek(device, start) }
        if (!autoplay) pause(device)
    }
    fun play(device: CastDevice) { soap(device, "Play", mapOf("Speed" to "1")) }
    fun pause(device: CastDevice) {
        runCatching { soap(device, "Pause") }.getOrElse { soap(device, "Stop") }
    }
    fun stop(device: CastDevice) { soap(device, "Stop") }
    fun seek(device: CastDevice, position: Long) {
        val seconds = position.coerceAtLeast(0) / 1000
        val time = java.lang.String.format(java.util.Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        soap(device, "Seek", mapOf("Unit" to "REL_TIME", "Target" to time))
    }
    fun position(device: CastDevice): CastPosition {
        val doc = xml(soap(device, "GetPositionInfo"))
        fun time(tag: String): Long {
            val parts = doc.getElementsByTagNameNS("*", tag).item(0)?.textContent.orEmpty().split(':')
            if (parts.size != 3) return 0
            return ((parts[0].toLongOrNull() ?: 0) * 3600 + (parts[1].toLongOrNull() ?: 0) * 60 +
                (parts[2].toDoubleOrNull() ?: 0.0).toLong()) * 1000
        }
        return CastPosition(time("RelTime"), time("TrackDuration"))
    }
}
