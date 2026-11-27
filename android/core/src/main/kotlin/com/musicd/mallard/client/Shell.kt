package com.musicd.mallard.client

import java.net.URLDecoder
import java.net.URLEncoder

/*
 * The native app's frame (old/plan-android-native.md): the screens it shows, by route, and the colours they are
 * drawn in. Every screen is the app's own since bug list item 111: there is no page under them.
 */

/** The shell's routes, Home ([HOME]) the first. */
object ShellRoute {
    const val ZONE = "zone"
    /** The native Now playing (N2), of a room. */
    const val NOW_PLAYING = "nowplaying/{$ZONE}"

    /** The route to Now playing in the room [zoneId]. */
    fun nowPlaying(zoneId: String): String = "nowplaying/" + enc(zoneId)

    const val OFFSET = "offset"
    const val LIT = "lit"
    const val NAV = "nav"
    const val AT = "at"
    /**
     * The native album page (N3), of an album, played in a room; [LIT] is the row's button lit under it ("home",
     * "library", or none); [NAV] names the list it was opened from (for previous and next; the app holds the list)
     * and [AT] where in it this album is.
     */
    const val ALBUM = "album/{$OFFSET}?$ZONE={$ZONE}&$LIT={$LIT}&$NAV={$NAV}&$AT={$AT}"

    /** The route to the album [offset]'s page, its buttons playing in [zoneId], the page's [lit] button under it, in list [nav] at [at]. */
    fun album(offset: Long, zoneId: String, lit: String = "", nav: String = "", at: Int = -1): String =
        "album/$offset?$ZONE=" + enc(zoneId) + "&$LIT=" + enc(lit) + "&$NAV=" + enc(nav) + "&$AT=$at"

    const val KEY = "key"
    /** One of the phone's own albums (item 108), by its [KEY] (LocalMusic's): its page, played on this phone; [ZONE] the room the mini player follows. */
    const val PHONE_ALBUM = "phone-album/{$KEY}?$ZONE={$ZONE}"

    /** The route to the phone's own album [key]'s page. */
    fun phoneAlbum(key: String, zoneId: String): String = "phone-album/" + enc(key) + "?$ZONE=" + enc(zoneId)

    const val VIEW = "view"
    const val PREFS = "prefs"
    /**
     * The native Library wall (N4, L1): [VIEW] the page's view of it as its query string (the sort, its direction,
     * the focus), [PREFS] the page's UI Settings as JSON (Wall.kt), [ZONE] the room its album pages play in.
     */
    const val LIBRARY = "library?$VIEW={$VIEW}&$PREFS={$PREFS}&$ZONE={$ZONE}"

    /** The route to the Library wall in the view [view] (a query string), drawn to [prefs] (JSON), its albums playing in [zoneId]. */
    fun library(view: String, prefs: String, zoneId: String): String = "library?$VIEW=" + enc(view) + "&$PREFS=" + enc(prefs) + "&$ZONE=" + enc(zoneId)

    const val KIND = "kind"
    const val FILTER = "filter"
    /**
     * A wall Home links to (L5, item 52): [KIND] later, favourites, history, unplayed or random; a genre's wall (item 57:
     * genre, with [FILTER] the filter as its query string, WallFilter); the app's own (downloads, phone); [ZONE] the room its albums play in.
     */
    const val WALL = "wall/{$KIND}?$ZONE={$ZONE}&$FILTER={$FILTER}"
    fun wall(kind: String, zoneId: String, filter: String = ""): String = "wall/" + enc(kind) + "?$ZONE=" + enc(zoneId) + "&$FILTER=" + enc(filter)

    /** Home (N5, item 57): drawn to the page's UI Settings ([PREFS], JSON) with its room ([ZONE]), as the page hands them over. */
    const val HOME = "home?$PREFS={$PREFS}&$ZONE={$ZONE}"
    fun home(prefs: String, zoneId: String): String = "home?$PREFS=" + enc(prefs) + "&$ZONE=" + enc(zoneId)

    /** Smart Picks (item 57): every pick with its reason and what can be done with it. */
    const val PICKS = "picks?$ZONE={$ZONE}"
    fun picks(zoneId: String): String = "picks?$ZONE=" + enc(zoneId)

    const val Q = "q"
    /** The search (item 61): the words it opens with ([Q], none for an empty field), its albums playing in [ZONE]. */
    const val SEARCH = "search?$Q={$Q}&$ZONE={$ZONE}"
    fun search(q: String, zoneId: String): String = "search?$Q=" + enc(q) + "&$ZONE=" + enc(zoneId)

    const val SERVICE = "service"
    const val ARTIST_ID = "artistId"
    /** A service's browser (item 73): Qobuz or Tidal, opened on its first tab, or straight on one of its artists ([ARTIST_ID] and [NAME]), or a search ([Q]). */
    const val SERVICE_BROWSER = "service/{$SERVICE}?$ARTIST_ID={$ARTIST_ID}&name={name}&q={q}&$ZONE={$ZONE}"
    fun serviceBrowser(service: String, zoneId: String, artistId: String = "", artistName: String = "", q: String = ""): String =
        "service/" + enc(service) + "?$ARTIST_ID=" + enc(artistId) + "&name=" + enc(artistName) + "&q=" + enc(q) + "&$ZONE=" + enc(zoneId)

    const val PANE = "pane"
    /** Settings (item 76): its list ("home"), Setup's ("setup"), or one of its pages the app draws ("account"). */
    const val SETTINGS = "settings/{$PANE}"
    fun settings(pane: String = "home"): String = "settings/" + enc(pane)


    /** The Artists wall (item 130): the row's Artists; an artist's page ([artist]) over it. */
    const val ARTISTS = "artists?$ZONE={$ZONE}"
    fun artists(zoneId: String): String = "artists?$ZONE=" + enc(zoneId)

    const val REVIEW = "review"
    /** Pitchfork (item 70): the magazine, or one review's page in it ([REVIEW], the review as JSON; none for the list). */
    const val PITCHFORK = "pitchfork?$REVIEW={$REVIEW}&$ZONE={$ZONE}"
    fun pitchfork(review: String, zoneId: String): String = "pitchfork?$REVIEW=" + enc(review) + "&$ZONE=" + enc(zoneId)

    /** The Shelf (item 207): the library as a record shop's shelf, played in [ZONE]. */
    const val SHELF = "shelf?$ZONE={$ZONE}"
    fun shelf(zoneId: String): String = "shelf?$ZONE=" + enc(zoneId)

    /** An artist's page (L4, item 52): their albums and their biography. */
    const val ARTIST = "artist?name={name}&$ZONE={$ZONE}"
    fun artist(name: String, zoneId: String): String = "artist?name=" + enc(name) + "&$ZONE=" + enc(zoneId)

    const val BOOKLET_ID = "booklet"
    const val NAME = "name"
    const val PAGES = "pages"
    /** An album's booklet, its pages one under another (the album page's book). */
    const val BOOKLET = "booklet/{$OFFSET}/{$BOOKLET_ID}?$NAME={$NAME}&$PAGES={$PAGES}"

    /** The route to booklet [id] of album [offset]: [name] its title, [pages] how many. */
    fun booklet(offset: Long, id: String, name: String, pages: Int): String = "booklet/$offset/" + enc(id) + "?$NAME=" + enc(name) + "&$PAGES=$pages"

    /** The room a `nowplaying` route's own text names (Navigation hands its argument over decoded already). */
    fun zoneOf(arg: String?): String = URLDecoder.decode(arg.orEmpty(), "UTF-8")

    // One argument, as an address writes it: a space as %20, which Navigation reads back as one ("+" it leaves).
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * Mandarin's own colours for the native screens: graphite and brass, the page's
 * dark "hifi" palette (style.css, `[data-theme="dark"][data-palette="hifi"]`),
 * so a native screen and the page beside it are one app. ARGB.
 */
object Palette {
    const val BG = 0xFF17191CL
    const val BG_ELEV = 0xFF1F2226L
    const val BG_ELEV_2 = 0xFF262A2FL
    const val BORDER = 0xFF30353BL
    const val TEXT = 0xFFECEBE6L
    const val TEXT_DIM = 0xFF9A9CA1L
    const val TEXT_FAINT = 0xFF7A7D83L
    const val ACCENT = 0xFFC9A45CL
    const val ACCENT_TEXT = 0xFFD4B06AL
    const val ON_ACCENT = 0xFF17191CL
    const val DANGER = 0xFFE8836BL
    /** The mini player's body (`--mt-bg`, rgba(31,34,38,.97)). */
    const val MT_BG = 0xF71F2226L
    /** The level meter's unlit segments (`--meter-off`, rgba(236,235,230,.12)). */
    const val METER_OFF = 0x1FECEBE6L
    /** The mini player's edge: the brass at .24 (style.css, `.mini-transport` in the hifi palette). */
    const val MT_EDGE = 0x3DC9A45CL
    /** A lit mode button's ground (`--accent-soft`, the brass at .15). */
    const val ACCENT_SOFT = 0x26C9A45CL
    /** The album view's and Now playing's text: white, not the cream (style.css, `.modal { --text: #ffffff }` in the hifi palette). */
    const val MODAL_TEXT = 0xFFFFFFFFL
    /** A cover's ring: white at .06 (style.css, the framed cover's box-shadow). */
    const val COVER_RING = 0x0FFFFFFFL
    /** The heart on an album's page, once tapped (style.css, `.fav-btn.is-on`). */
    const val HEART = 0xFFE0463FL
    /** An open track row's wash: white at .05 (style.css, `.t-row.is-open`). */
    const val ROW_OPEN = 0x0DFFFFFFL
    /** The volume sheet's glass (`--bg-veil`, the ground at .88). */
    const val BG_VEIL = 0xE017191CL
    /** The volume sheet's edge (`--glass-edge`, the brass at .22). */
    const val GLASS_EDGE = 0x38C9A45CL
    /** A row's rule on a panel (`--panel-hairline`, the cream at .08). */
    const val PANEL_HAIRLINE = 0x14ECEBE6L

    /** Each colour by the page's own token name, for the test that holds the two together. */
    val tokens: Map<String, Long> = mapOf(
        "bg" to BG, "bg-elev" to BG_ELEV, "bg-elev-2" to BG_ELEV_2, "border" to BORDER, "text" to TEXT, "text-dim" to TEXT_DIM,
        "text-faint" to TEXT_FAINT, "accent" to ACCENT, "accent-text" to ACCENT_TEXT, "on-accent" to ON_ACCENT, "danger" to DANGER
    )

    /** The translucent ones, by token name: the page writes these as rgba(r,g,b,a). */
    val rgbaTokens: Map<String, Long> = mapOf("mt-bg" to MT_BG, "meter-off" to METER_OFF, "accent-soft" to ACCENT_SOFT, "bg-veil" to BG_VEIL, "glass-edge" to GLASS_EDGE, "panel-hairline" to PANEL_HAIRLINE)
}
