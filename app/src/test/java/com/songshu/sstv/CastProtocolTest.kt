package com.songshu.sstv

import com.songshu.sstv.cast.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class CastProtocolTest {
    @Test fun discoveryHeadersAndDeviceDescriptionResolveRelativeControlEndpoint() {
        assertEquals("http://192.168.1.8/device.xml", DlnaCast.location("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.8/device.xml\r\n"))
        val result = DlnaCast.parseDevice("http://192.168.1.8/devices/root.xml", """
          <root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>客厅电视</friendlyName>
          <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
          <controlURL>../control/av</controlURL></service></serviceList></device></root>
        """.trimIndent())!!
        assertEquals("客厅电视", result.name)
        assertEquals("http://192.168.1.8/control/av", result.controlUrl)
    }

    @Test fun castingLoadsEscapedUrlAndSupportsSeekPauseAndPosition() {
        val server = MockWebServer(); server.start()
        try {
            repeat(4) { server.enqueue(MockResponse().setBody("<ok/>")) }
            val device = CastDevice("电视", "", server.url("/control").toString(), "urn:schemas-upnp-org:service:AVTransport:1")
            DlnaCast.load(device, "https://video.example/test.m3u8?a=1&b=2", "影片 & 测试", 32_000, false)
            val set = server.takeRequest()
            assertEquals("\"${device.serviceType}#SetAVTransportURI\"", set.getHeader("SOAPAction"))
            val body = set.body.readUtf8()
            assertTrue(body.contains("test.m3u8?a=1&amp;b=2"))
            assertTrue(body.contains("DIDL-Lite"))
            assertTrue(server.takeRequest().getHeader("SOAPAction")!!.contains("#Play"))
            assertTrue(server.takeRequest().body.readUtf8().contains("00:00:32"))
            assertTrue(server.takeRequest().getHeader("SOAPAction")!!.contains("#Pause"))
            server.enqueue(MockResponse().setBody("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><GetPositionInfoResponse><RelTime>00:02:03</RelTime><TrackDuration>01:30:00</TrackDuration></GetPositionInfoResponse></s:Body></s:Envelope>"))
            assertEquals(CastPosition(123_000, 5_400_000), DlnaCast.position(device))
        } finally { server.shutdown() }
    }

    @Test fun externalEntityDescriptionsAreRejected() {
        assertTrue(runCatching {
            DlnaCast.parseDevice("http://device/root", "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///example'>]><root>&x;</root>")
        }.isFailure)
    }
}
