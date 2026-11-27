package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share card as the server answers and sharecard.js lays it out (item 75); widths from a stand-in font, half a size a letter. */
class ShareCardTest {
    private val font = { s: String, _: Int, size: Int -> s.length * size * 0.5f }

    @Test fun `a card of art, title and artist is the 600 it always was, its block centred in the pane`() {
        val l = CardLayout.measure(font, "White Sky", "Peter Green", "1982", "", "")
        assertEquals(600, l.cardH)
        assertEquals("Released 1982", l.metaText)
        assertEquals(listOf("White Sky"), l.title.lines)
        assertEquals(56, l.title.size)
        assertEquals(68, l.title.lh)
        assertEquals(listOf("by Peter Green"), l.artist.lines)
        assertNull(l.desc)
        assertEquals("the cover is the taller column", 464, l.headerH)
        // The pane the whole card (item 387): the 464px cover centred in the 600, 68 from the top.
        assertEquals("no inset", 0, CardLayout.INSET)
        assertEquals((600 - 464) / 2, l.artY)
        assertEquals(68 + Math.round((464 - l.headerTextH) / 2.0).toInt(), l.textY)
    }

    @Test fun `a long title steps down in size before it is cut, and only the smallest size is cut with an ellipsis`() {
        // In the 580px column (item 387): 26 three-letter words take five lines at 48 and four at 42.
        assertEquals(580, CardLayout.TEXT_W)
        val mid = CardLayout.fit(font, "abc ".repeat(26).trim(), CardLayout.TEXT_W, 4, 700, listOf(56, 48, 42, 36, 31, 27), 68.0 / 56)
        assertEquals("four lines first fit at 42", 42, mid.size)
        assertEquals(4, mid.lines.size)
        assertFalse(mid.lines.last().endsWith("…"))
        val long = CardLayout.fit(font, "word ".repeat(200).trim(), CardLayout.TEXT_W, 4, 700, listOf(56, 48, 42, 36, 31, 27), 68.0 / 56)
        assertEquals(27, long.size)
        assertEquals(4, long.lines.size)
        assertTrue(long.lines.last().endsWith("…"))
        assertTrue("the cut line still fits", font(long.lines.last(), 700, 27) <= CardLayout.TEXT_W)
    }

    @Test fun `a review grows the card to hold it, with whose words they are, and the longest the page sends comes through whole`() {
        val review = ShareCard.trimReview("Alpha beta gamma delta. ".repeat(80))
        val l = CardLayout.measure(font, "White Sky", "Peter Green", "", review, "Wikipedia")
        val desc = l.desc!!
        assertEquals("33 on 49 since the pane is the whole card (item 387); 31 on 46 before", 49, desc.lh)
        assertEquals(33, desc.size)
        assertFalse("not cut", desc.lines.last().endsWith("…") && !review.endsWith("…"))
        assertEquals(review.split(Regex("\\s+")).size, desc.lines.sumOf { it.split(" ").size })
        assertEquals("Wikipedia", l.srcText)
        assertEquals(464 + 30 + 1 + 30 + desc.lines.size * 49 + 26 + 24, l.contentH)
        assertEquals("its contents 56 from the card's edges, no pane inset", l.contentH + 56 * 2, l.cardH)
        assertEquals(56 + 464 + 30, l.ruleY)
        assertEquals("no review, no source", "", CardLayout.measure(font, "T", "A", "", "", "Wikipedia").srcText)
    }

    @Test fun `the release written as the card writes it`() {
        assertEquals("20 Nov 2006", CardLayout.releaseDate("2006-11-20"))
        assertEquals("Nov 2006", CardLayout.releaseDate("2006-11"))
        assertEquals("2006", CardLayout.releaseDate(" 2006 "))
        assertEquals("2006-13", CardLayout.releaseDate("2006-13"))
        assertNull(CardLayout.releaseDate(""))
    }

    @Test fun `the review trimmed to ten sentences and 1400 characters, cut at a word`() {
        assertEquals("as the page joins them: each sentence keeps its own space before it", (1..10).joinToString(" ") { if (it == 1) "S1." else " S$it." }, ShareCard.trimReview((1..14).joinToString(" ") { "S$it." }))
        val t = ShareCard.trimReview("word ".repeat(400))
        assertTrue(t.length <= 1399)
        assertTrue(t.endsWith("word…"))
    }

    @Test fun `what the card says, from the extras answer, the review always where there is one (item 336)`() {
        val j = JSONObject("""{"year":2006,"album":{"score":8,"isBestNewMusic":true,"description":"Good. Very good.","description_source":"Wikipedia"}}""")
        val f = CardFields.parse(j)
        assertEquals("2006", f.releaseRaw)
        assertEquals("Good. Very good.", f.reviewText)
        assertEquals("Wikipedia", f.reviewSource)
        assertEquals(8.0, f.score!!, 0.0)
        assertTrue(f.bestNew)
        assertEquals("2006|Good. Very good.|Wikipedia|8|true", f.signature)
        val once = CardFields.parse(JSONObject(j.toString()).put("card", JSONObject().put("review", false)))
        assertEquals("a review once switched off in Settings is on the card now", "Good. Very good.", once.reviewText)
        assertEquals("Wikipedia", once.reviewSource)
        val none = CardFields.parse(JSONObject("""{"year":2006,"album":{"description_source":"Wikipedia"}}"""))
        assertEquals("no review: no one's words", listOf("", ""), listOf(none.reviewText, none.reviewSource))
        assertEquals(CardFields.NONE, CardFields.parse(null))
        assertEquals("Peter_Green-White_Sky.png", ShareCard.fileName("White Sky", "Peter Green"))
    }

    @Test fun `this server's descriptionSource is whose words they are`() {
        val f = CardFields.parse(JSONObject("""{"year":1997,"album":{"description":"Fine.","descriptionSource":"AllMusic","score":null}}"""))
        assertEquals("AllMusic", f.reviewSource)
        assertNull("a null score is no score", f.score)
        assertEquals("1997|Fine.|AllMusic||false", f.signature)
    }
}
