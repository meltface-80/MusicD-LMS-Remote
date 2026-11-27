package com.musicd.mallard

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.musicd.mallard.client.LibraryView
import com.musicd.mallard.client.ScreenPrefs
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShellRoute
import com.musicd.mallard.client.WallPrefs
import org.json.JSONObject

/**
 * The app's frame (old/plan-android-native.md): Compose, its navigation and Mallard's own colours, the whole
 * screen. Home is the first route; every other screen opens over the one that opened it, Back closing it. Since bug list
 * item 111 there is no page under it: what the screens once handed to the page is the app's own ([handOn], ShellHand.kt),
 * and the screen settings the page kept on this phone (the UI Settings, the Library's view, the share card's service) are
 * the app's to keep ([keep]; core ScreenPrefs), with the room ([keepZone]).
 */
internal object NativeShell {
    /** The shell's navigation, while it is on screen (set by [Shell]). */
    internal var nav: NavHostController? = null

    /** A screen opened over the one on screen. */
    fun open(route: String) { nav?.navigate(route) { launchSingleTop = true } }

    /** The Library's view (its query string), as the app keeps it: Home's Library row is the head of that list. */
    @Volatile var libraryView = ""
    /** Home's arguments: the screen settings as JSON, and the room; Home follows them. */
    val homeArgs = mutableStateOf("" to "")
    /**
     * The screen settings as JSON (core ScreenPrefs), and the room: the screens draw to them and play in it. The settings
     * are Compose state (item 190): a screen reading them is drawn again when UI Settings changes one, not at the next start.
     */
    private val prefsNow = mutableStateOf<String?>(null)
    var prefs: String?
        get() = prefsNow.value
        set(v) { prefsNow.value = v }
    @Volatile var zone: String = ""
    /** Where they are kept (set by [attach]). */
    private var store: Context? = null

    /** How many of the app's sheets have saved something over a screen: a screen whose list a sheet can change reads it again. */
    val sheetsClosed = mutableStateOf(0)
    /**
     * The app on screen (between MainActivity's onResume and onPause; item 120): what the screens ask the server for
     * again and again (a room followed, today's album, the notices, the Tailscale status) is asked only while it is, not
     * all night behind a phone switched off.
     */
    val onScreen = mutableStateOf(false)
    /** The side menu open over the screen (item 67; MenuDrawer.kt): the row's Menu opens it, Back closes it. */
    val menuOpen = mutableStateOf(false)

    /** A screen setting [name] (as ScreenPrefs names it) set to [value], or taken out: kept, and Home drawn to it. */
    fun keep(name: String, value: String?) = keepAll(ScreenPrefs.with(prefs ?: store?.let { Store.homePrefs(it) }, name, value))

    /** The screen settings as a whole ([json]): kept, the Library's view and Home following them. */
    fun keepAll(json: String) {
        prefs = json
        libraryView = LibraryView.kept(runCatching { JSONObject(json).optString(ScreenPrefs.VIEW) }.getOrDefault(""))
        store?.let { Store.setHomePrefs(it, json) }
        homeArgs.value = json to homeArgs.value.second
    }

    /** The Library's view changed (its wall): Home's Library row follows, and the next start. */
    fun keepLibraryView(q: String) { val k = LibraryView.kept(q); if (k != libraryView) keep(ScreenPrefs.VIEW, k.ifEmpty { null }) }   // never the name filter (item 338)

    /** The room the screens play in, chosen or grouped: kept for the next start. */
    fun keepZone(id: String?) {
        if (id.isNullOrEmpty()) return
        zone = id
        homeArgs.value = homeArgs.value.first to id
        // The notification and lock screen follow the room chosen now, not at their next look at the rooms (item 120).
        store?.let { Store.setZone(it, id); NowPlayingService.start(it) }
    }

    /** A room chosen on a screen: Home's own room too, so Back finds it following that room (item 63); what's playing in the one left offered to move (item 68). */
    fun roomChosen(id: String) { RoomAsk.chosen(zone, id); keepZone(id) }

    /** Back on Home with the row's Home: its top. */
    fun landHome() { HomeKept.toTop = true }

    /** The row's button on Home: Home itself goes to its top; the Library and the search open over it. */
    fun homeNav(what: String) { nav?.let { navTap(it, what) } }

    /** Back: the side menu closes, else the screen on top (true); on Home, nothing of the app's to close (false). */
    fun back(): Boolean {
        if (menuOpen.value) { menuOpen.value = false; return true }
        val n = nav ?: return false
        if (n.currentDestination?.route == ShellRoute.HOME) return false
        return n.popBackStack()
    }

    /** The shell, the activity's whole screen: Home at once, drawn to the screen settings and in the room kept last. */
    fun attach(activity: ComponentActivity): ComposeView {
        store = activity.applicationContext
        prefs = Store.homePrefs(activity)
        libraryView = LibraryView.kept(runCatching { JSONObject(prefs ?: "{}").optString(ScreenPrefs.VIEW) }.getOrDefault(""))
        zone = Store.playRoom(activity)
        homeArgs.value = prefs.orEmpty() to zone
        return ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { MallardTheme { Shell(client = { Store.screenClient(activity) }) } }
        }
    }
}

/** The screens by route, Home the first. */
@Composable
private fun Shell(client: () -> ServerClient?) {
    val nav = rememberNavController()
    DisposableEffect(nav) {
        NativeShell.nav = nav
        onDispose { if (NativeShell.nav === nav) NativeShell.nav = null }
    }
    LogTrace(nav)   // the log's watch, while logging is on (item 121)
    LibraryLive.Follow(client)   // the server's albums followed while on screen (item 232)
    Box(Modifier.fillMaxSize().logTaps()) {
    NavHost(navController = nav, startDestination = ShellRoute.HOME, enterTransition = { androidx.compose.animation.EnterTransition.None }, exitTransition = { androidx.compose.animation.ExitTransition.None }, popEnterTransition = { androidx.compose.animation.EnterTransition.None }, popExitTransition = { androidx.compose.animation.ExitTransition.None }) {   // no fade between screens (item 72): Navigation's own left the screen before as a ghost for 700 ms
        // Home (N5, item 57): its rows' walls, Smart Picks and an album's page open over it, and Back comes back to it as it was.
        composable(ShellRoute.HOME, arguments = listOf(navArgument(ShellRoute.PREFS) { type = NavType.StringType; defaultValue = "" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) {
            val args by NativeShell.homeArgs
            HomeScreen(WallPrefs.parse(args.first), args.second, client,
                onAlbum = { offset, zone, list -> nav.navigate(ShellRoute.album(offset, zone, "home", AlbumLists.put(list), list.index)) },
                onWall = { kind, filter -> nav.navigate(ShellRoute.wall(kind, args.second, filter)) },
                onLibrary = { nav.navigate(ShellRoute.library(NativeShell.libraryView, args.first, args.second)) },
                onPicks = { nav.navigate(ShellRoute.picks(args.second)) },
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                onRoom = NativeShell::roomChosen,
                onNav = NativeShell::homeNav,
                onPage = { what, arg -> handOver(nav, what, arg) }
            )
        }
        composable(ShellRoute.PICKS, arguments = listOf(navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty()
            PicksScreen(WallPrefs.parse(NativeShell.prefs), zone, client,
                onBack = { nav.popBackStack() },
                onAlbum = { offset, z -> nav.navigate(ShellRoute.album(offset, z, "")) },
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                onRoom = NativeShell::roomChosen,
                onNav = { what -> navTap(nav, what) },
                onPage = { what, arg -> handOver(nav, what, arg) }
            )
        }
        // The search (item 61): over Home or the page; an album's page over it, and Back comes back to the same results.
        // The search (item 61) and the rest: ShellRoutes.kt.
        moreRoutes(nav, client)
        composable(ShellRoute.NOW_PLAYING, arguments = listOf(navArgument(ShellRoute.ZONE) { type = NavType.StringType })) { entry ->
            NowPlayingScreen(
                entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), client, onBack = { nav.popBackStack() },
                // Another room from Play on: its Now playing in this one's place, and the room the screens play in too.
                onRoom = { id ->
                    nav.navigate(ShellRoute.nowPlaying(id)) { popUpTo(ShellRoute.NOW_PLAYING) { inclusive = true } }
                    NativeShell.roomChosen(id)
                },
                // The album (found by NowAlbum) in its place; an artist over it.
                onPage = { what, arg -> handOver(nav, what, arg) },
                onNav = { what -> navTap(nav, what) }   // the row along the foot (item 296)
            )
        }
        // The Library wall (N4, item 51): in the view kept; an album's page over it, Back returning here.
        composable(ShellRoute.LIBRARY, arguments = listOf(
            navArgument(ShellRoute.VIEW) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.PREFS) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" }
        )) { entry ->
            LibraryScreen(
                // The screen settings as they are now (item 190), not as they were when it opened.
                entry.arguments?.getString(ShellRoute.VIEW).orEmpty(), WallPrefs.parse(NativeShell.prefs ?: entry.arguments?.getString(ShellRoute.PREFS)),
                entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), client,
                onBack = { nav.popBackStack() },
                onAlbum = { offset, zone, list -> nav.navigate(ShellRoute.album(offset, zone, "library", AlbumLists.put(list), list.index)) },
                // The view changed here: Home's Library row follows, and the next opening.
                onView = NativeShell::keepLibraryView,
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                onRoom = NativeShell::roomChosen,
                onNav = { what -> navTap(nav, what) },
                onPage = { what, arg -> handOver(nav, what, arg) }
            )
        }
        // The walls Home links to (L5) and an artist's page (L4), item 52: each over the screen that opened it.
        composable(ShellRoute.WALL, arguments = listOf(navArgument(ShellRoute.KIND) { type = NavType.StringType }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.FILTER) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty()
            WallScreen(entry.arguments?.getString(ShellRoute.KIND).orEmpty(), entry.arguments?.getString(ShellRoute.FILTER).orEmpty(), WallPrefs.parse(NativeShell.prefs), zone, client,
                onBack = { nav.popBackStack() },
                onAlbum = { offset, z, list -> nav.navigate(ShellRoute.album(offset, z, "", AlbumLists.put(list), list.index)) },
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                onRoom = NativeShell::roomChosen,
                onNav = { what -> navTap(nav, what) },
                onPage = { what, arg -> handOver(nav, what, arg) }
            )
        }
        composable(ShellRoute.ARTIST, arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" }, navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" })) { entry ->
            val zone = entry.arguments?.getString(ShellRoute.ZONE).orEmpty()
            ArtistScreen(entry.arguments?.getString("name").orEmpty(), WallPrefs.parse(NativeShell.prefs), zone, client,
                onBack = { nav.popBackStack() },
                onAlbum = { offset, z, list -> nav.navigate(ShellRoute.album(offset, z, "", AlbumLists.put(list), list.index)) },
                onArtist = { other -> nav.navigate(ShellRoute.artist(other, zone)) },
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                onRoom = NativeShell::roomChosen,
                onNav = { what -> navTap(nav, what) },
                onPage = { what, arg -> handOver(nav, what, arg) }
            )
        }
        composable(ShellRoute.ALBUM, arguments = listOf(
            navArgument(ShellRoute.OFFSET) { type = NavType.LongType },
            navArgument(ShellRoute.ZONE) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.LIT) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.NAV) { type = NavType.StringType; defaultValue = "" },
            navArgument(ShellRoute.AT) { type = NavType.IntType; defaultValue = -1 }
        )) { entry ->
            val lit = entry.arguments?.getString(ShellRoute.LIT).orEmpty()
            val navId = entry.arguments?.getString(ShellRoute.NAV).orEmpty()
            AlbumScreen(
                entry.arguments?.getLong(ShellRoute.OFFSET) ?: 0L, entry.arguments?.getString(ShellRoute.ZONE).orEmpty(), lit, client,
                list = AlbumLists.get(navId.ifEmpty { null }, entry.arguments?.getInt(ShellRoute.AT) ?: -1),
                // Previous, next, or a disc of the box: that album's page in this one's place, in the same list.
                onStepTo = { offset, zone, l -> nav.navigate(ShellRoute.album(offset, zone, lit, AlbumLists.put(l), l.index)) { popUpTo(ShellRoute.ALBUM) { inclusive = true } } },
                onBack = { nav.popBackStack() },
                // The row along the foot: back to the wall it came from (Library), else as the row does anywhere.
                onNav = { what -> if (!(what == "library" && nav.popBackStack(ShellRoute.LIBRARY, false))) navTap(nav, what) },
                onPage = { what, arg -> handOver(nav, what, arg) },
                // The mini player's tap: the native Now playing over the album.
                onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
                // A room chosen on its Play on: the room the screens play in.
                onRoom = NativeShell::roomChosen,
                // Another album of the library's, from the rows under the tracks: its own page over this one; Back returns here.
                onAlbum = { id, zone -> nav.navigate(ShellRoute.album(id, zone, lit)) }
            )
        }
    }
    MenuOver(nav, client)   // the side menu over whichever screen is on (item 67)
    RoomAskOver(client)   // the question whether to move what's playing to the room chosen (item 68)
    }
}
