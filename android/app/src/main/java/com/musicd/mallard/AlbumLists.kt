package com.musicd.mallard

import com.musicd.mallard.client.NavList

/**
 * The lists albums were opened from (a Home row, the Library wall, a box
 * set's discs), held for the album page's previous and next: the screen
 * that opens an album hands its list over ([put]), the route names it by a short id (a list of hundreds is no address), and the
 * page of each album stepped to carries the same id with its own place.
 */
internal object AlbumLists {
    // The 32 last used, the oldest let go one at a time (item 159: all were cleared at once, lists on the back stack with them).
    private val lists = object : LinkedHashMap<String, NavList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NavList>?) = size > 32
    }
    private var next = 1
    /** This run's own: an album page restored after Android ended the app names a list of the last run, never one of this. */
    private val run = Integer.toHexString(java.util.Random().nextInt(0xffff))

    /** [list] held under a new id, which the route carries. */
    @Synchronized fun put(list: NavList): String {
        val id = "l$run-${next++}"
        lists[id] = list
        return id
    }

    /** The list [id] names, at [at]; null without one. */
    @Synchronized fun get(id: String?, at: Int): NavList? {
        val l = lists[id ?: return null] ?: return null
        return if (at in l.items.indices) l.copy(index = at) else null
    }
}
