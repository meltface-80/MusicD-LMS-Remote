package com.musicd.mallard.client

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Now playing's format line (owner request: the quality on Now playing for every zone, with ReplayGain while levelling
 * applies; This phone's "Opus 256 kbps"): the server's words drawn as they come (lib/nowquality.js), and the mini player's
 * short form — the format alone, without its kbps.
 */
class NowFormatTest {
    private fun view(kind: String, text: String) = NowPlayingView.of(ZoneState.parse(JSONObject("""
        {"zone":{"zone_id":"aa","display_name":"Kitchen","state":"playing","now_playing":{"line1":"Angel","line2":"Massive Attack","line3":"Mezzanine",
         "format":{"kind":"$kind","text":"$text"}}}}""")), 0)!!

    @Test fun theServersWordsAsTheyCome() {
        assertEquals("FLAC 24/96 · ReplayGain −6.2 dB", view("lossless", "FLAC 24/96 · ReplayGain −6.2 dB").formatText)
        assertEquals("Opus 256 kbps · 24/48", view("opus", "Opus 256 kbps · 24/48").formatText)
    }

    @Test fun anOlderOpusTextIsNamed() = assertEquals("Opus 256kbps", view("opus", "256kbps").formatText)

    @Test fun theMiniPlayersShortForm() {
        assertEquals("FLAC 24/96", view("lossless", "FLAC 24/96 · ReplayGain −6.2 dB").formatWord)
        assertEquals("Opus 256", view("opus", "Opus 256 kbps · 24/48").formatWord)
        assertEquals("MP3 320", view("lossy", "MP3 320 kbps").formatWord)
    }
}
