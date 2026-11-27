package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {
    // /api/queue's answer: two to come (the first playing), two played, newest first.
    private val view = QueueView.parse(JSONObject(
        """{"items": [{"queue_item_id": 2, "title": "Song 2", "subtitle": "Artist A", "album": "Album One", "image_key": "al-1", "length": 183},
                      {"queue_item_id": 3, "title": "Song 3", "subtitle": "Artist A", "album": "Album One", "image_key": null, "length": null}],
            "history": [{"track": "Song 1", "artist": "Artist A", "album": "Album One", "image_key": "al-1", "duration": 200, "elapsed": 12, "played": false},
                        {"track": "Song 0", "artist": "Artist B", "album": "Other", "duration": 61, "elapsed": 61, "played": true}]}"""
    ))

    @Test fun `the queue read as the page reads it`() {
        assertEquals(listOf(2L, 3L), view.items.map { it.id })
        assertEquals(listOf("al-1", null), view.items.map { it.imageKey })
        assertEquals(listOf(183, null), view.items.map { it.lengthSeconds })
        assertEquals("2 tracks · 3:03 remaining", view.summary)
        assertEquals("2 played earlier", view.historyText)
        assertEquals("oldest first, the latest against Now playing", listOf("Song 0", "Song 1"), view.playedInOrder.map { it.track })
        assertFalse(view.empty)
        assertTrue(QueueView(emptyList(), emptyList()).empty)
        assertEquals("Nothing more queued", QueueView(emptyList(), view.history).summary)
        assertEquals("1 track · 1:00 remaining", QueueView(listOf(QueueItem(1, "a", "", "", null, 60)), emptyList()).summary)
    }

    @Test fun `a skip reads against its length, a played track as its length`() {
        assertEquals("0:12 / 3:20", view.history[0].lengthText)
        assertEquals("1:01", view.history[1].lengthText)
    }

    @Test fun `played from here, the tracks above it move to played at once`() {
        val v = view.playedFrom(1)
        assertEquals(listOf("Song 3"), v.items.map { it.title })
        assertEquals(listOf("Song 2", "Song 1", "Song 0"), v.history.map { it.track })
        assertEquals("nothing to move for the one playing", view, view.playedFrom(0))
        val three = QueueView(listOf("a", "b", "c").mapIndexed { i, t -> QueueItem(i.toLong(), t, "", "", null, 1) }, emptyList())
        assertEquals("the latest played first, as the server sends them", listOf("b", "a"), three.playedFrom(2).history.map { it.track })
    }

    @Test fun `the questions asked first, in the page's words`() {
        assertEquals("Play from \"Song 3\"?", QueueView.askPlayFrom("Song 3"))
        assertEquals("Play from \"this track\"?", QueueView.askPlayFrom(""))
        assertEquals("Play \"Song 1\" next?", QueueView.askPlayNext("Song 1"))
        assertEquals("Remove \"Song 3\" from the queue?", QueueView.askRemove(listOf("Song 3")))
        assertEquals("Remove 2 tracks from the queue?", QueueView.askRemove(listOf("a", "b")))
        assertEquals("Add 2 tracks to the end of the queue?", QueueView.askHistory("queue", listOf("a", "b")))
        assertEquals("Play \"a\" next?", QueueView.askHistory("play_next", listOf("a")))
        assertEquals("Clear the queue? Playback stops.", QueueView.ASK_CLEAR)
    }
}
