package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mallard against MusicD LMS Remote's answers as index.js writes them (the fixtures are copied from its handlers),
 * and the bodies Mallard sends, as the handlers read them.
 */
class MallardApiTest {

    /** A transport that records every request and answers from [answers] by path (query dropped). */
    private class Recorder(private val answers: Map<String, String> = emptyMap()) : Transport {
        val sent = ArrayList<Triple<String, String, JSONObject?>>()
        override fun json(method: String, path: String, body: JSONObject?, timeout: Int): JSONObject {
            sent += Triple(method, path, body)
            return JSONObject(answers[path.substringBefore('?')] ?: """{"ok": true}""")
        }
        override fun bytes(path: String, timeout: Int): ByteArray = ByteArray(0)
    }

    private fun client(r: Recorder) = ServerClient(ServerAddress("192.168.1.10", 3390), transport = r)

    private val zoneState = """{"zone": {
        "zone_id": "aa:bb:cc:dd:ee:ff", "display_name": "Kitchen", "state": "playing",
        "shuffle": 1, "repeat": 2, "radio": true,
        "is_play_allowed": true, "is_pause_allowed": true, "is_next_allowed": true, "is_previous_allowed": true, "is_seek_allowed": true,
        "outputs": [{"output_id": "aa:bb:cc:dd:ee:ff", "display_name": "Kitchen", "is_muted": false,
            "volume": {"value": 42, "min": 0, "max": 100, "step": 1, "soft_limit": 100, "type": "number"}}],
        "now_playing": {"line1": "Teardrop", "line2": "Massive Attack", "line3": "Mezzanine", "image_key": "a3f9c210",
            "length": 330.4, "seek_position": 57.2}}}"""

    @Test fun `the zone's state, LMS's numbered modes read as the screen's`() {
        val z = ZoneState.parse(JSONObject(zoneState)).zone!!
        assertEquals("Kitchen", z.name)
        assertTrue(z.isPlaying)
        assertTrue(z.shuffle)
        assertEquals("repeat 2 is the queue", "loop", z.loop)
        assertTrue(z.radio)
        assertEquals(42, z.volume)
        val np = z.nowPlaying!!
        assertEquals("Teardrop", np.title); assertEquals("Massive Attack", np.artist); assertEquals("Mezzanine", np.album)
        assertEquals(330, np.lengthSeconds); assertEquals(57, np.seekSeconds)
        val one = ZoneState.parse(JSONObject(zoneState.replace("\"repeat\": 2", "\"repeat\": 1"))).zone!!
        assertEquals("repeat 1 is the track", "loop_one", one.loop)
    }

    @Test fun `a fixed-volume player has no scale, and nothing playing is no view`() {
        val fixed = ZoneState.parse(JSONObject(zoneState.replace(Regex("\"volume\": \\{[^}]*\\}"), "\"volume\": null"))).zone!!
        assertNull(fixed.volume)
        assertEquals(42.0, NowPlayingView.of(ZoneState.parse(JSONObject(zoneState)), 0)!!.volume!!.value!!, 0.0)
        val idle = ZoneState.parse(JSONObject(zoneState.replace(Regex("\"now_playing\": \\{[^}]*\\}"), "\"now_playing\": null")))
        assertNull(NowPlayingView.of(idle, 0))
        assertNull(ZoneState.parse(JSONObject("""{"zone": null}""")).zone)
    }

    @Test fun `the clock moves the track on while it plays, never past its end`() {
        val v = NowPlayingView.of(ZoneState.parse(JSONObject(zoneState)), 1_000)!!
        assertEquals(57.0, v.position(1_000), 0.001)
        assertEquals(67.0, v.position(11_000), 0.001)
        assertEquals(330.0, v.position(10_000_000), 0.001)
        assertEquals("loop_one", v.loopNext)
    }

    @Test fun `shuffle and repeat go to the player's mode route as numbers`() {
        val r = Recorder(); val c = client(r)
        c.zoneSettings("aa:bb", loop = "loop")
        c.zoneSettings("aa:bb", shuffle = false)
        assertEquals("/api/lms/player/aa%3Abb/mode", r.sent[0].second)
        assertEquals(2, r.sent[0].third!!.getInt("repeat"))
        assertFalse(r.sent[0].third!!.has("shuffle"))
        assertEquals(0, r.sent[1].third!!.getInt("shuffle"))
    }

    @Test fun `transport, volume and transfer name the player as the handlers read it`() {
        val r = Recorder(); val c = client(r)
        c.control("p1", "playpause"); c.seek("p1", 12.5); c.zoneVolumeStep("p1", -1); c.mute("p1", true); c.transferZone("p1", "p2")
        assertEquals(listOf("/api/control", "/api/seek", "/api/volume", "/api/volume", "/api/transfer-zone"), r.sent.map { it.second })
        assertEquals("p1", r.sent[0].third!!.getString("zone_or_output_id"))
        assertEquals(12.5, r.sent[1].third!!.getDouble("seconds"), 0.0)
        assertEquals(-2, r.sent[2].third!!.getInt("relative"))
        assertTrue(r.sent[3].third!!.getBoolean("mute"))
        assertEquals("p1", r.sent[4].third!!.getString("from_zone")); assertEquals("p2", r.sent[4].third!!.getString("to_zone"))
    }

    @Test fun `queue removals go highest place first, so none has moved when it is reached`() {
        val r = Recorder(); val c = client(r)
        c.queueRemove("p1", listOf(4, 9, 6))
        assertEquals(listOf(9L, 6L, 4L), r.sent.map { it.third!!.getLong("queue_item_id") })
    }

    @Test fun `an album page, the offset asked for, each track its place, lengths added`() {
        val j = JSONObject("""{"album": {"title": "Mezzanine", "subtitle": "Massive Attack", "image_key": "a3f9c210", "year": 1998,
            "merge_id": null, "part_count": null, "quality": "16/44.1", "hires": false},
            "tracks": [{"title": "Angel", "subtitle": "Massive Attack"}, {"title": "Risingson", "subtitle": "Massive Attack", "length": 298.0}],
            "actions": [{"kind": "play_now", "title": "Play Now"}, {"kind": "queue", "title": "Queue"}, {"kind": "play_next", "title": "Next"}]}""")
        val a = Album.parse(j, 812)
        assertEquals(812L, a.album.offset)
        assertEquals(1998, a.album.year)
        assertEquals(listOf(0L, 1L), a.tracks.map { it.id })
        assertEquals("Massive Attack", a.tracks[0].sub)
        assertEquals("4:58", a.tracks[1].lengthText)
        assertEquals(listOf("play_now", "queue", "play_next"), a.actions.map { it.kind })
    }

    @Test fun `a track plays by its album's offset and its place`() {
        val r = Recorder(); val c = client(r)
        c.playTrack(812, 3, "p1", "play_next")
        val b = r.sent[0].third!!
        assertEquals("/api/play-track", r.sent[0].second)
        assertEquals(812L, b.getLong("offset")); assertEquals(3, b.getInt("track")); assertEquals("play_next", b.getString("kind"))
    }

    @Test fun `favourites without an offset are left off a wall`() {
        val r = Recorder(mapOf("/api/favourites" to """{"total": 2, "albums": [
            {"key": "mezzanine|massive attack", "title": "Mezzanine", "subtitle": "Massive Attack", "image_key": "a3f9c210", "offset": 812, "at": 1},
            {"key": "x|y", "title": "X", "subtitle": "Y", "image_key": null, "offset": null, "at": 2}]}"""))
        val row = LibraryClient(client(r)).favourites()
        assertEquals(listOf(812L), row.albums.map { it.offset })
        assertTrue(row.albums[0].favourite)
    }

    @Test fun `the server is known by its status, and a stranger is refused`() {
        val h = Health.parse(JSONObject("""{"paired": true, "core_id": "u", "core_name": "Lyrion Music Server 9.0.2", "zone_count": 3}"""))
        assertTrue(h.connected); assertEquals(3, h.rooms); assertEquals("Lyrion Music Server 9.0.2", h.lms)
        assertTrue(runCatching { Health.parse(JSONObject("""{"ok": true}""")) }.isFailure)
    }

    @Test fun `covers are asked by key, clamped to the server's sizes`() {
        val c = client(Recorder())
        assertEquals("http://192.168.1.10:3390/api/image/url-aHR0cHM6Ly9h?size=1200", c.imageUrl("url-aHR0cHM6Ly9h", 4000))
        assertEquals("http://192.168.1.10:3390/imageproxy/x/image.jpg", c.absoluteUrl("/imageproxy/x/image.jpg"))
        assertEquals(3390, ServerAddress.parse("192.168.1.10", "").port)
    }
}
