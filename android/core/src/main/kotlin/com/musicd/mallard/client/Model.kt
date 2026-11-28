package com.musicd.mallard.client

import org.json.JSONObject

/**
 * What /api/status says about a MusicD LMS Remote server — enough to know it is the right one: [connected] whether it
 * reaches its Lyrion Music Server, [lms] that server's name, [rooms] its players.
 */
data class Health(val version: String, val connected: Boolean, val lms: String, val rooms: Int, val tailscale: String? = null) {
    companion object {
        fun parse(j: JSONObject): Health {
            // Every MusicD LMS Remote answers with "paired"; newer ones also name themselves.
            require(j.has("paired") || j.optString("app") == "musicd-lms-remote") { "That isn't a Mallard server" }
            return Health(
                version = j.optString("version", "?"),
                connected = j.optBoolean("paired", false),
                lms = j.optString("core_name", "").takeIf { !j.isNull("core_name") } ?: "",
                rooms = j.optInt("zone_count", 0),
                // The server's own address on the tailnet ("http://100.x.y.z:3390") while its built-in Tailscale serves.
                tailscale = if (!j.has("tailscale") || j.isNull("tailscale")) null else j.optString("tailscale").ifEmpty { null }
            )
        }
    }
}

data class ZoneSummary(val id: String, val name: String, val state: String)

data class Zones(val zones: List<ZoneSummary>, val lastZone: String?) {
    companion object {
        fun parse(j: JSONObject): Zones {
            val arr = j.optJSONArray("zones")
            val list = ArrayList<ZoneSummary>()
            if (arr != null) for (i in 0 until arr.length()) {
                val z = arr.optJSONObject(i) ?: continue
                val id = z.optString("zone_id", "")
                if (id.isEmpty()) continue
                list += ZoneSummary(id, z.optString("display_name", ""), z.optString("state", "stopped"))
            }
            val last = j.optString("last_zone", "").takeIf { it.isNotEmpty() && !j.isNull("last_zone") }
            return Zones(list, last)
        }
    }
}

/** One of a track's artists; [linkable] when the library has an artist page for them. */
data class Credit(val name: String, val linkable: Boolean)

/** What the room is hearing, as the page's Now playing badges it: [kind] lossless, opus, a codec; [text] its words. */
data class Format(val kind: String, val text: String)

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val imageKey: String?,
    val lengthSeconds: Int?,
    val seekSeconds: Int?,
    val artists: List<Credit> = emptyList(),
    /** The library's album the track is from, when it is one. */
    val albumId: String? = null,
    val format: Format? = null,
    /** The library's track it is, when it is one: the album page marks it. */
    val trackId: Long? = null
)

/** A speaker's volume as the server gives it: its range and step, where it is; `incremental` has no scale, only up and down. */
data class VolumeScale(val min: Double, val max: Double, val step: Double, val value: Double?, val type: String?) {
    val incremental: Boolean get() = type == "incremental"

    /** A step up ([direction] 1) or down (-1) from where it is, within the scale; null where it isn't known. */
    fun stepped(direction: Int): Double? = value?.let { (it + direction * step).coerceIn(min, max) }
}

data class Output(val id: String, val name: String, val volume: Int?, val muted: Boolean, val scale: VolumeScale? = null)

data class Zone(
    val id: String,
    val name: String,
    val state: String,
    val nowPlaying: NowPlaying?,
    val outputs: List<Output>,
    val nextAllowed: Boolean,
    val previousAllowed: Boolean,
    val shuffle: Boolean = false,
    /** Repeat: `disabled`, `loop` (the queue) or `loop_one` (the track). */
    val loop: String = "disabled",
    /** LMS's own shuffle mode as it reports it: 0 off, 1 by track, 2 by album. */
    val shuffleMode: Int = 0,
    /** Random album radio (the server's, per player) is on for this room. */
    val radio: Boolean = false
) {
    val isPlaying: Boolean get() = state == "playing" || state == "loading"

    /** The room's volume as one number: the mean of its speakers. */
    val volume: Int? get() = outputs.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.average()?.toInt()
}

/** A room as the Play on list shows it: its name, and its speakers when it is more than one. */
data class Room(val id: String, val name: String, val outputs: List<String>) {
    companion object {
        /** /api/zones' answer. */
        fun list(j: JSONObject): List<Room> {
            val arr = j.optJSONArray("zones") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("zone_id", "").isNotEmpty() }.map { z ->
                val outs = z.optJSONArray("outputs")
                Room(z.getString("zone_id"), z.optString("display_name", ""),
                    if (outs == null) emptyList() else (0 until outs.length()).mapNotNull { outs.optJSONObject(it)?.optString("display_name", "")?.takeIf { n -> n.isNotEmpty() } })
            }
        }
    }
}

/** One answer from /api/zone-state: the zone, and the revision to wait on next. */
data class ZoneState(val revision: Long, val zone: Zone?) {
    companion object {
        fun parse(j: JSONObject): ZoneState {
            val rev = j.optLong("revision", 0L)
            val z = j.optJSONObject("zone") ?: return ZoneState(rev, null)
            val np = z.optJSONObject("now_playing")
            val outs = ArrayList<Output>()
            val arr = z.optJSONArray("outputs")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val v = o.optJSONObject("volume")
                outs += Output(
                    id = o.optString("output_id", ""),
                    name = o.optString("display_name", ""),
                    volume = if (v != null && !v.isNull("value")) v.optInt("value") else null,
                    muted = o.optBoolean("is_muted", false),
                    scale = v?.let {
                        val max = it.optDouble("max", 100.0)
                        VolumeScale(
                            min = it.optDouble("min", 0.0),
                            max = if (it.has("soft_limit") && !it.isNull("soft_limit")) minOf(max, it.optDouble("soft_limit")) else max,
                            step = it.optDouble("step", 1.0).takeIf { s -> s > 0 } ?: 1.0,
                            value = if (it.isNull("value")) null else it.optDouble("value"),
                            type = it.optString("type", "").takeIf { t -> t.isNotEmpty() }
                        )
                    }
                )
            }
            return ZoneState(
                rev,
                Zone(
                    id = z.optString("zone_id", ""),
                    name = z.optString("display_name", ""),
                    state = z.optString("state", "stopped"),
                    nowPlaying = np?.let {
                        NowPlaying(
                            title = it.optString("line1", ""),
                            artist = it.optString("line2", ""),
                            album = it.optString("line3", ""),
                            imageKey = it.optString("image_key", "").takeIf { k -> k.isNotEmpty() && !it.isNull("image_key") },
                            lengthSeconds = if (it.isNull("length")) null else it.optInt("length"),
                            seekSeconds = if (it.isNull("seek_position")) null else it.optInt("seek_position"),
                            artists = it.optJSONArray("artists")?.let { a ->
                                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { c -> Credit(c.optString("name", ""), c.optBoolean("linkable", false)) }
                            }.orEmpty(),
                            albumId = if (it.isNull("album_offset")) null else it.opt("album_offset")?.toString(),
                            format = it.optJSONObject("format")?.let { f -> Format(f.optString("kind", ""), f.optString("text", "")) },
                            trackId = if (it.isNull("track_id")) null else it.optLong("track_id")
                        )
                    },
                    outputs = outs,
                    nextAllowed = z.optBoolean("is_next_allowed", true),
                    previousAllowed = z.optBoolean("is_previous_allowed", true),
                    // LMS spells the modes as numbers (playlist shuffle / playlist repeat): shuffle 0 off, 1 tracks,
                    // 2 albums; repeat 0 off, 1 the track, 2 the queue. Painted from here, never guessed.
                    shuffle = z.optInt("shuffle", 0) != 0,
                    loop = when (z.optInt("repeat", 0)) { 1 -> "loop_one"; 2 -> "loop"; else -> "disabled" },
                    shuffleMode = z.optInt("shuffle", 0),
                    radio = z.optBoolean("radio", false)
                )
            )
        }
    }
}
