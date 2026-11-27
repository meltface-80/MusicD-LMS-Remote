package com.musicd.mallard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.PitchforkHit
import com.musicd.mallard.client.ShellRoute
import com.musicd.mallard.client.WallPrefs

/*
 * The native screens' routes past NativeShell.kt's own (it draws nothing; nor does
 * this): the search (item 61), Settings (item 76), each over
 * the screen that opened it, Back returning there.
 */
internal fun NavGraphBuilder.moreRoutes(nav: NavHostController, client: () -> ServerClient?) {
    composable(ShellRoute.SEARCH, arguments = listOf(navArgument(ShellRoute.Q) { type = NavType.StringType; defaultValue = "" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        SearchScreen(WallPrefs.parse(NativeShell.prefs), entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), client,
            onBack = { nav.popBackStack() },
            onAlbum = { offset, z, list -> nav.navigate(ShellRoute.album(offset, z, "", AlbumLists.put(list), list.index)) },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
            onRoom = NativeShell::roomChosen,
            onNav = { what -> navTap(nav, what) },
            // A Pitchfork review: its page in the app's own magazine (item 70).
            onPage = { what, arg ->
                when {
                    what == "review" && arg != null -> nav.navigate(ShellRoute.pitchfork(arg, NativeShell.zone))
                    // A service's artist: their albums in the app's own browser (item 73).
                    what == "service-artist" && arg != null -> runCatching { org.json.JSONObject(arg) }.getOrNull()?.let { j ->
                        nav.navigate(ShellRoute.serviceBrowser(j.optString("service"), NativeShell.zone, j.optString("id"), j.optString("name"))) }
                    else -> handOver(nav, what, arg)
                }
            }
        )
    }
    // Pitchfork (item 70): the magazine, or a review's page in it; an album the library has opens in its place, Find on Qobuz the app's browser in its place (item 73).
    composable(ShellRoute.PITCHFORK, arguments = listOf(navArgument(ShellRoute.REVIEW) { type = NavType.StringType; defaultValue = "" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty()
        PitchforkScreen(WallPrefs.parse(NativeShell.prefs), PitchforkHit.parse(entry.arguments?.getString(ShellRoute.REVIEW)), client,
            onClose = { nav.popBackStack() },
            onAlbum = { offset -> inPlace(nav, ShellRoute.album(offset, zone, "")) },
            onQobuz = { q -> inPlace(nav, ShellRoute.serviceBrowser("qobuz", zone, q = q)) })
    }
    // The Qobuz and Tidal browsers (item 73), each opening kept while on the back stack (its entry's id); an album's page over it.
    composable(ShellRoute.SERVICE_BROWSER, arguments = listOf(navArgument(ShellRoute.SERVICE) { type = NavType.StringType }, navArgument(ShellRoute.ARTIST_ID) { type = NavType.StringType; defaultValue = "" },
        navArgument("name") { type = NavType.StringType; defaultValue = "" }, navArgument("q") { type = NavType.StringType; defaultValue = "" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        val a = entry.arguments
        val zone = a?.getString(ShellRoute.ZONE).orEmpty()
        val kept = ServiceBrowsers.kept(entry.id, a?.getString(ShellRoute.SERVICE).orEmpty())
        ServiceBrowserScreen(kept, (a?.getString(ShellRoute.ARTIST_ID).orEmpty() to a?.getString("name").orEmpty()).takeIf { it.first.isNotEmpty() }, a?.getString("q").orEmpty(),
            WallPrefs.parse(NativeShell.prefs), client, onClose = { nav.popBackStack() }, onAlbum = { offset -> nav.navigate(ShellRoute.album(offset, zone, "")) })
    }
    // The Shelf (item 207): the side menu's Shelf, natively; Back (or its pill) returns to the screen under it.
    composable(ShellRoute.SHELF, arguments = listOf(navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        // An album opened on it is the library's album page (item 218), over the shelf; what that asks for past an album, as the page's.
        ShelfScreen(entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), client, onBack = { nav.popBackStack() },
            onPage = { what, arg -> handOver(nav, what, arg) },
            onBooklet = { offset, b -> nav.navigate(ShellRoute.booklet(offset, b.id, b.name, b.pages)) },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } })
    }
    // Settings (item 76): its list, Setup's, and every page of it.
    composable(ShellRoute.SETTINGS, arguments = listOf(navArgument(ShellRoute.PANE) { type = NavType.StringType; defaultValue = "home" })) { entry ->
        SettingsScreen(entry.arguments?.getString(ShellRoute.PANE).orEmpty(), client,
            onBack = { nav.popBackStack() },
            onOpen = { pane -> nav.navigate(ShellRoute.settings(pane)) }
        )
    }
    // The Artists wall (item 130), the row's Artists; an artist's page over it.
    composable(ShellRoute.ARTISTS, arguments = listOf(navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty()
        ArtistsScreen(WallPrefs.parse(NativeShell.prefs), zone, client,
            onBack = { nav.popBackStack() },
            onArtist = { name -> nav.navigate(ShellRoute.artist(name, zone)) },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
            onRoom = NativeShell::roomChosen,
            onNav = { what -> navTap(nav, what) },
            onPage = { what, arg -> handOver(nav, what, arg) }
        )
    }
    // One of the phone's own albums (item 108): the album page, read from the phone and played on it.
    composable(ShellRoute.PHONE_ALBUM, arguments = listOf(navArgument(ShellRoute.KEY) { type = NavType.StringType }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        AlbumScreen(-1L, entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), "", client, list = null, onStepTo = { _, _, _ -> },
            onBack = { nav.popBackStack() }, onPage = { what, arg -> handOver(nav, what, arg) }, onBooklet = { _, _ -> },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } }, onRoom = NativeShell::roomChosen,
            onNav = { what -> navTap(nav, what) }, onAlbum = { id, z -> nav.navigate(ShellRoute.album(id, z, "")) },
            phone = entry.arguments?.getString(ShellRoute.KEY).orEmpty())
    }
}

/**
 * What a screen hands on: an artist's page, the phone's own album, each over the screen that asked;
 * Now playing's album ([findNowAlbum]) in Now playing's place; an album no longer in the library closed; the rest the
 * app's own ([handOn]).
 */
internal fun handOver(nav: NavHostController, what: String, arg: String?) {
    AppLog.line("HAND", "$what ${logArg(arg)}")
    when {
        what == "artist" && !arg.isNullOrEmpty() -> nav.navigate(ShellRoute.artist(arg, NativeShell.zone))
        what == "genre" && !arg.isNullOrEmpty() -> nav.navigate(ShellRoute.wall("genre", NativeShell.zone, com.musicd.mallard.client.WallFilter.routeQuery(com.musicd.mallard.client.WallFilter("genre", arg))))   // a genre's albums (item 139)
        what == "phone-album" && !arg.isNullOrEmpty() -> nav.navigate(ShellRoute.phoneAlbum(arg, NativeShell.zone))   // the phone's own album (item 108)
        what == "album" && arg != null && arg.toLongOrNull() != null -> inPlace(nav, ShellRoute.album(arg.toLong(), NativeShell.zone, ""))
        what == "library-changed" && arg != null -> nav.popBackStack()   // a streamed album taken out of the favourites: its page closes
        else -> handOn(what, arg)
    }
}

/**
 * Every screen over Home closed, Home itself kept: it is the first route, every other screen opens over it, and Back
 * on the next screen comes back to it (bug list item 115: the menu's items popped Home itself when opened from Home,
 * so Settings and the rest had nothing under them, and their ✕ and ‹ did nothing). A stack without Home
 * gets it back.
 */
internal fun toHome(nav: NavHostController) {
    if (nav.popBackStack(ShellRoute.HOME, false) || nav.currentDestination?.route == ShellRoute.HOME) return
    nav.navigate(ShellRoute.home(NativeShell.homeArgs.value.first, NativeShell.homeArgs.value.second)) { popUpTo(nav.graph.id) { inclusive = true } }
}

/**
 * The row along the foot: Menu opens the side menu over the screen showing (item 296); the rest close every screen over
 * Home (however deep: a wall, an album from it, an artist from that; item 63), then Home goes to its top, or the Library
 * wall (in the view kept), the Artists wall (item 130), the Shelf (item 210) or the search opens over it.
 */
internal fun navTap(nav: NavHostController, what: String) {
    AppLog.line("ROW", "button $what")
    when (what) {
        "home" -> { toHome(nav); NativeShell.landHome() }
        // Opened over Home in one step (item 199): Home was drawn for a moment between the screen left and this one.
        "library" -> overHome(nav, ShellRoute.library(NativeShell.libraryView, NativeShell.prefs.orEmpty(), NativeShell.zone))
        "artists" -> overHome(nav, ShellRoute.artists(NativeShell.zone))
        "search" -> { SearchKept.reset(""); overHome(nav, ShellRoute.search("", NativeShell.zone)) }
        "shelf" -> overHome(nav, ShellRoute.shelf(NativeShell.zone))   // item 210: the row's own, as the side menu's
        "menu" -> NativeShell.menuOpen.value = true   // over the screen showing (item 296); an item chosen closes the screens over Home (menuTap)
    }
}

/**
 * [route] opened over Home in one step, every screen between them taken away with it (item 199): before, they were
 * taken away first and Home drawn for a frame or more before [route] came. A stack without Home gets it back first.
 */
internal fun overHome(nav: NavHostController, route: String) {
    if (runCatching { nav.getBackStackEntry(ShellRoute.HOME) }.isFailure) toHome(nav)
    nav.navigate(route) { popUpTo(ShellRoute.HOME) }
}

/** [route] in place of the screen showing, in one step (item 199): before, it was closed first and the one under it drawn. */
internal fun inPlace(nav: NavHostController, route: String) {
    val here = nav.currentBackStackEntry?.destination?.id
    nav.navigate(route) { if (here != null) popUpTo(here) { inclusive = true } }
}
