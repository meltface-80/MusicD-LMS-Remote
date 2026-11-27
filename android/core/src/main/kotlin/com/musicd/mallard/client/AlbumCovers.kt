package com.musicd.mallard.client

/**
 * An album's cover as its page reads it, for the lists kept from before (bug list item 123): a cover found or changed in
 * the editor has a new key, and a list loaded earlier would go on drawing the old one.
 */
object AlbumCovers {
    /** The places in [albums] where album [offset] shows another cover than [imageKey], and each put right. */
    fun fixes(albums: List<AlbumSummary>, offset: Long, imageKey: String?): List<Pair<Int, AlbumSummary>> =
        albums.withIndex().filter { (_, a) -> a.offset == offset && a.imageKey != imageKey }.map { (i, a) -> i to a.copy(imageKey = imageKey) }
}
