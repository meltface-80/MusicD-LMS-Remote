package com.musicd.mallard

import androidx.compose.ui.graphics.ImageBitmap
import com.musicd.mallard.client.ArtistAbout
import com.musicd.mallard.client.ArtistAlbums
import com.musicd.mallard.client.ArtistBio
import com.musicd.mallard.client.ArtistSimilar
import com.musicd.mallard.client.SimilarAlbum
import com.musicd.mallard.client.TimedCache

/** What an artist's page had when last open (bug list item 140): drawn at once as it was when opened again, picture and all. */
internal data class ArtistPage(val albums: ArtistAlbums? = null, val about: ArtistAbout? = null, val bio: ArtistBio? = null, val similar: ArtistSimilar? = null,
                               val similarAlbums: List<SimilarAlbum>? = null, val portrait: ImageBitmap? = null)

/** The last two dozen artists' pages, asked for afresh after fifteen minutes (until then, not asked for at all). */
internal object ArtistPages {
    val kept = TimedCache<String, ArtistPage>(capacity = 24, ttlMs = 15 * 60_000L)
    fun key(name: String) = name.trim().lowercase()
}
