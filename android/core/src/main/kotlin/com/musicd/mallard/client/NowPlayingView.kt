package com.musicd.mallard.client

/**
 * What the native Now playing shows of a room (stage N2 of
 * old/plan-android-native.md): its track, cover and transport, and
 * where the track is. The server says where it was when it answered
 * ([Zone]'s seek); between answers, while it plays, the screen moves it on by
 * the clock itself, never past the track's end.
 */
data class NowPlayingView(
    val zoneName: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageKey: String?,
    val playing: Boolean,
    /** Where the track was when the server answered, in seconds. */
    val seekSeconds: Double,
    val lengthSeconds: Double?,
    val canNext: Boolean,
    val canPrevious: Boolean,
    /** When the answer came ([System.currentTimeMillis] then). */
    val answeredAtMs: Long,
    val shuffle: Boolean = false,
    /** Repeat: disabled, loop (the queue), loop_one (the track). */
    val loop: String = "disabled",
    val format: Format? = null,
    /** The room's volume, from its first speaker that has one; null: fixed. */
    val volume: VolumeScale? = null,
    val muted: Boolean = false,
    /** The track's artists, each a link when the library has their page; empty from an older server (show [artist]). */
    val artists: List<Credit> = emptyList(),
    /** The library's track playing, when it is one. */
    val trackId: Long? = null,
    /** The phone's own album playing, its key ([NowAlbum.phoneKey]), when it is one. */
    val phoneAlbum: String? = null,
    /** The album playing, as the server names it, when it is one. */
    val albumId: String? = null,
    /** LMS's shuffle mode: 0 off, 1 tracks, 2 albums. */
    val shuffleMode: Int = 0,
    /** Random album radio is on for this room. */
    val radio: Boolean = false
) {
    /** The lines under the album, centred: the format badge's words. */
    val infoLines: List<String> get() = listOfNotNull(formatText)

    /** Shuffle's state in the contract's labels: Shuffle, Shuffle on. */
    val shuffleState: Int get() = if (shuffle) 1 else 0

    /** Repeat's state in the contract's labels: off, the queue, the track. */
    val loopState: Int get() = LOOPS.indexOf(loop).coerceAtLeast(0)

    /** What a tap on repeat asks for: off → the queue → the track → off, as the page's does. */
    val loopNext: String get() = LOOPS[(loopState + 1) % LOOPS.size]

    /** The format as the page's badge reads it: an Opus stream is named. */
    val formatText: String? get() = format?.let { if (it.kind == "opus") "Opus ${it.text}".trim() else it.text.ifEmpty { null } }

    /** The format as the mini player's kicker reads it, after the room: "Lossless", "Opus 256" (no kbps there). */
    val formatWord: String? get() = format?.let { f ->
        (if (f.kind == "opus") "Opus " + f.text.replace(Regex("\\s*kbps$", RegexOption.IGNORE_CASE), "") else f.text).trim().ifEmpty { null }
    }

    /** A volume a step up ([direction] 1) or down (-1) from where it is, within its scale. */
    fun volumeStep(direction: Int): Double? = volume?.stepped(direction)

    /** Where the track is at [nowMs]: moved on by the clock while it plays. */
    fun position(nowMs: Long): Double {
        val p = if (playing) seekSeconds + (nowMs - answeredAtMs).coerceAtLeast(0) / 1000.0 else seekSeconds
        return lengthSeconds?.let { p.coerceAtMost(it) } ?: p
    }

    /** How far through the track, 0 to 1; 0 when its length isn't known. */
    fun fraction(nowMs: Long): Float = lengthSeconds?.takeIf { it > 0 }?.let { (position(nowMs) / it).toFloat().coerceIn(0f, 1f) } ?: 0f

    companion object {
        private val LOOPS = listOf("disabled", "loop", "loop_one")

        /** The room's answer as the screen shows it; null when nothing is playing there. */
        fun of(state: ZoneState, answeredAtMs: Long): NowPlayingView? {
            val z = state.zone ?: return null
            val np = z.nowPlaying ?: return null
            return NowPlayingView(
                zoneName = z.name, title = np.title, artist = np.artist, album = np.album, imageKey = np.imageKey,
                playing = z.isPlaying, seekSeconds = (np.seekSeconds ?: 0).toDouble(), lengthSeconds = np.lengthSeconds?.toDouble(),
                canNext = z.nextAllowed, canPrevious = z.previousAllowed, answeredAtMs = answeredAtMs,
                shuffle = z.shuffle, loop = z.loop, format = np.format,
                volume = z.outputs.firstNotNullOfOrNull { it.scale }, muted = z.outputs.any { it.muted }, artists = np.artists, trackId = np.trackId,
                phoneAlbum = null, albumId = np.albumId, shuffleMode = z.shuffleMode, radio = z.radio
            )
        }

        /** A time as the page writes it: "0:03", "4:05", "1:02:03". */
        fun clock(seconds: Double): String {
            val s = seconds.coerceAtLeast(0.0).toLong()
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
