package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Home's layout read and saved one call at a time (item 83). */
class HomeLayoutSyncTest {
    /** A server that keeps the layout, its saves slow until [let] is counted down. */
    private class Server {
        @Volatile var kept = listOf(HomeRow("later", true, null), HomeRow("history", true, null))
        val saved = mutableListOf<String>()
        val entered = CountDownLatch(1)
        val let = CountDownLatch(1)
        fun get(): List<HomeRow> = kept
        fun post(body: JSONObject): JSONObject {
            entered.countDown()
            let.await(5, TimeUnit.SECONDS)
            val rows = body.getJSONArray("rows")
            kept = (0 until rows.length()).map { HomeRow(rows.getJSONObject(it).getString("id"), rows.getJSONObject(it).getBoolean("on"), null) }
            synchronized(saved) { saved += kept.joinToString(",") { it.id } }
            return body
        }
    }

    private fun ids(rows: List<HomeRow>?) = rows?.joinToString(",") { it.id }

    @Test fun `Home reading the layout while an order is still being saved is answered the order saved`() {
        val server = Server()
        val save = HomeLayoutSync().let { sync ->
            val s = sync.save(listOf(HomeRow("history", true, null), HomeRow("later", true, null)), server::post)
            assertEquals(true, server.entered.await(5, TimeUnit.SECONDS))
            val read = sync.load(server::get)
            Thread.sleep(100)
            server.let.countDown()
            assertEquals("history,later", ids(read.get(5, TimeUnit.SECONDS)))
            s
        }
        assertEquals("history,later", ids(save.get(5, TimeUnit.SECONDS)))
    }

    @Test fun `saves made in the order asked, only the last one's answer shown`() {
        val server = Server()
        val sync = HomeLayoutSync()
        // The first held at the server until the second has been asked for, so the second is the later one asked.
        val first = sync.save(listOf(HomeRow("history", true, null), HomeRow("later", true, null)), server::post)
        val second = sync.save(listOf(HomeRow("later", false, null), HomeRow("history", true, null)), server::post)
        server.let.countDown()
        assertNull(first.get(5, TimeUnit.SECONDS))
        assertEquals("later,history", ids(second.get(5, TimeUnit.SECONDS)))
        assertEquals(listOf("history,later", "later,history"), server.saved)
    }

    @Test fun `what the server says went wrong is thrown`() {
        val sync = HomeLayoutSync()
        val r = runCatching { sync.save(emptyList()) { JSONObject().put("error", "no") }.get(5, TimeUnit.SECONDS) }
        assertEquals("no", r.exceptionOrNull()?.cause?.message)
    }
}
