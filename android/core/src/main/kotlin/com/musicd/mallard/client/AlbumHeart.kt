package com.musicd.mallard.client

/**
 * The button before Play Now on an album's page (item 539), as the page's album-heart.js: a Qobuz or Tidal album that
 * isn't among your favourites there is a + (a tap adds it there); otherwise Mandarin's own heart, hollow, red once
 * liked. Whether it is a favourite there is known only while signed in ([Album.serviceFavourite] null otherwise): then
 * the heart as ever. Add to and Remove from the service's favourites stay in ⋯.
 */
object AlbumHeart {
    const val ADD = "add"
    const val HEART = "heart"
    const val LIKED = "liked"

    /** What the button is for an album from [source] ("qobuz", "tidal", "local"), a favourite there or not, liked in Mandarin or not. */
    fun of(source: String?, serviceFavourite: Boolean?, favourite: Boolean): String =
        if (serviceName(source) != null && serviceFavourite == false) ADD else if (favourite) LIKED else HEART

    /** The service's name as the page says it; null for your own files. */
    fun serviceName(source: String?): String? = when (source) { "qobuz" -> "Qobuz"; "tidal" -> "Tidal"; else -> null }
}
