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
            onQobuz = { q -> inPlace(nav, ShellRoute.search(q, zone)) })
    }
    // The Shelf: the library as a record shop's racks, played in the room chosen (the server's /shelf, natively).
    composable(ShellRoute.SHELF, arguments = listOf(navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        ShelfScreen(entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), client, onBack = { nav.popBackStack() },
            onPage = { what, arg -> handOver(nav, what, arg) }, onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } })
    }
    // The plugins (LMS apps and radio): their list, and any of their menus, each over the screen that opened it.
    composable(ShellRoute.APPS, arguments = listOf(navArgument(ShellRoute.KIND) { type = NavType.StringType; defaultValue = "app" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty().ifEmpty { NativeShell.zone }
        AppsScreen(entry.arguments?.getString(ShellRoute.KIND).orEmpty(), zone, client,
            onBack = { nav.popBackStack() },
            onOpen = { e -> nav.navigate(ShellRoute.appMenu(e.go, e.name, NativeShell.zone)) },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
            onRoom = NativeShell::roomChosen,
            onNav = { what -> navTap(nav, what) },
            onPage = { what, arg -> handOver(nav, what, arg) })
    }
    composable(ShellRoute.APP_MENU, arguments = listOf(
        navArgument(ShellRoute.TOKEN) { type = NavType.StringType },
        navArgument(ShellRoute.TITLE) { type = NavType.StringType; defaultValue = "" },
        navArgument(ShellRoute.TERM) { type = NavType.StringType; defaultValue = "" },
        navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
        val a = entry.arguments
        AppMenuScreen(a?.getString(ShellRoute.TOKEN).orEmpty(), a?.getString(ShellRoute.TITLE).orEmpty(), a?.getString(ShellRoute.TERM).orEmpty(),
            a?.getString(ShellRoute.ZONE).orEmpty().ifEmpty { NativeShell.zone }, client,
            onBack = { nav.popBackStack() },
            onMenu = { token, title, term -> nav.navigate(ShellRoute.appMenu(token, title, NativeShell.zone, term)) },
            onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
            onRoom = NativeShell::roomChosen,
            onNav = { what -> navTap(nav, what) },
            onPage = { what, arg -> handOver(nav, what, arg) })
    }
    // Settings (item 76): its list, Setup's, and every page of it.
    composable(ShellRoute.SETTINGS, arguments = listOf(navArgument(ShellRoute.PANE) { type = NavType.StringType; defaultValue = "home" })) { entry ->
        SettingsScreen(entry.arguments?.getString(ShellRoute.PANE).orEmpty(), client,
            onBack = { nav.popBackStack() },
            onOpen = { pane -> nav.navigate(ShellRoute.settings(pane)) }
        )
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
        what == "album" && arg != null && arg.toLongOrNull() != null -> inPlace(nav, ShellRoute.album(arg.toLong(), NativeShell.zone, ""))
        what == "qobuz" -> nav.navigate(com.musicd.mallard.client.CollectionRoute.qobuz(NativeShell.zone))   // a search's Qobuz album (QobuzOpen)
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
        "search" -> { SearchKept.reset(""); overHome(nav, ShellRoute.search("", NativeShell.zone)) }
        "shelf" -> overHome(nav, ShellRoute.shelf(NativeShell.zone))
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
