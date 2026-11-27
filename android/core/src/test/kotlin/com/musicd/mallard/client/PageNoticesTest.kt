package com.musicd.mallard.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The page's notices as it tells the app of them (item 69, public/app/native-notices.js). */
class PageNoticesTest {
    @Test fun `the server's notice and the update offer read, an empty one none`() {
        val p = PageNotices.parse("""{"notice":{"lines":["The music folder “A” isn't there — …","  "],"error":true,"buttons":["Forget “A”"]},
            "update":{"text":"Mandarin v1.1 is ready","notes":"- faster","now":"Update","later":true,"error":false}}""")
        assertEquals(ServerNotice(listOf("The music folder “A” isn't there — …"), true, listOf("Forget “A”")), p.notice)
        assertEquals(UpdateOffer("Mandarin v1.1 is ready", "- faster", "Update", true, false), p.update)
        assertNull("busy: no Update to tap", PageNotices.parse("""{"update":{"text":"Downloading…","now":null,"later":false}}""").update!!.now)
        assertEquals(PageNotices(), PageNotices.parse("""{"notice":{"lines":[]},"update":{"text":""}}"""))
        assertEquals(PageNotices(), PageNotices.parse("not json"))
        assertEquals(PageNotices(), PageNotices.parse(null))
    }

    @Test fun `each button's question, by its place, asked by the app (item 96)`() {
        val n = PageNotices.parse("""{"notice":{"lines":["Two folders gone"],"error":true,"buttons":["Something else","Forget “A”"],
            "asks":["","Forget “A”? Its 3 tracks and their albums leave the library."]}}""").notice!!
        assertNull("none asked", n.ask(0))
        assertEquals("by its place", "Forget “A”? Its 3 tracks and their albums leave the library.", n.ask(1))
        assertNull("no such button", n.ask(2))
        assertNull("an older page tells none", PageNotices.parse("""{"notice":{"lines":["x"],"buttons":["Forget “A”"]}}""").notice!!.ask(0))
    }
}
