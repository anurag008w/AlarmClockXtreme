package com.sysadmindoc.alarmclock.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RssParserImageTest {

    @Test
    fun onlyHttpsImagesAreAccepted() {
        assertEquals("https://cdn.example.com/a.jpg", RssParser.safeImageUrl(" https://cdn.example.com/a.jpg "))
        assertNull(RssParser.safeImageUrl("http://cdn.example.com/a.jpg"))
        assertNull(RssParser.safeImageUrl("javascript:alert(1)"))
        assertNull(RssParser.safeImageUrl("file:///sdcard/a.jpg"))
        assertNull(RssParser.safeImageUrl(null))
        assertNull(RssParser.safeImageUrl(""))
    }

    @Test
    fun firstImageComesFromTheBodyHtml() {
        val html = "<p>Intro</p><img class=\"x\" src='https://cdn.example.com/lead.png'/><img src=\"https://cdn.example.com/second.png\">"
        assertEquals("https://cdn.example.com/lead.png", RssParser.firstImageInHtml(html))
        assertNull(RssParser.firstImageInHtml("<p>No picture</p>"))
        assertNull(RssParser.firstImageInHtml("<img src=\"http://insecure.example.com/a.png\">"))
    }
}
