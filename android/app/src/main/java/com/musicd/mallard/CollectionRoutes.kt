package com.musicd.mallard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.musicd.mallard.client.CollectionRoute
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShellRoute

/*
 * The side menu's collections (Labels, Merged albums, Playlists, Dynamic Playlists, Import a playlist): each screen over the one
 * that opened it, Back returning there; an album opened from one over it, with the list to step through.
 */
internal fun NavGraphBuilder.collectionRoutes(nav: NavHostController, client: () -> ServerClient?) {
    val chrome = CollectionNav(onBack = { nav.popBackStack() },
        onNowPlaying = { id -> nav.navigate(ShellRoute.nowPlaying(id)) { launchSingleTop = true } },
        onRoom = NativeShell::roomChosen, onNav = { what -> navTap(nav, what) }, onPage = { what, arg -> handOver(nav, what, arg) })
    fun str(k: String, d: String = "") = navArgument(k) { type = NavType.StringType; defaultValue = d }
    composable(CollectionRoute.MERGES, arguments = listOf(str(CollectionRoute.ZONE))) { e ->
        MergesScreen(e.arguments?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome,
            onAlbum = { offset -> nav.navigate(ShellRoute.album(offset, NativeShell.zone, "")) })
    }
    composable(CollectionRoute.PLAYLISTS, arguments = listOf(str(CollectionRoute.ZONE))) { e ->
        PlaylistsScreen(e.arguments?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome,
            onOpen = { p -> nav.navigate(CollectionRoute.playlist(p, NativeShell.zone)) })
    }
    composable(CollectionRoute.PLAYLIST, arguments = listOf(str(CollectionRoute.ID), str(CollectionRoute.NAME), str(CollectionRoute.OWN, "false"), str(CollectionRoute.ZONE))) { e ->
        val a = e.arguments
        PlaylistScreen(a?.getString(CollectionRoute.ID).orEmpty(), a?.getString(CollectionRoute.NAME).orEmpty(), a?.getString(CollectionRoute.OWN) == "true",
            a?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome)
    }
    composable(CollectionRoute.LIVE_PLAYLISTS, arguments = listOf(str(CollectionRoute.ZONE))) { e ->
        LivePlaylistsScreen(e.arguments?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome,
            onOpen = { p -> nav.navigate(CollectionRoute.livePlaylist(p, NativeShell.zone)) })
    }
    composable(CollectionRoute.LIVE_PLAYLIST, arguments = listOf(str(CollectionRoute.ID), str(CollectionRoute.NAME), str(CollectionRoute.ZONE))) { e ->
        val a = e.arguments
        LivePlaylistScreen(a?.getString(CollectionRoute.ID).orEmpty(), a?.getString(CollectionRoute.NAME).orEmpty(), a?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome,
            onAlbum = { offset, zone, list -> nav.navigate(ShellRoute.album(offset, zone, "", AlbumLists.put(list), list.index)) })
    }
    composable(CollectionRoute.LABELS, arguments = listOf(str(CollectionRoute.ZONE))) { e ->
        LabelsScreen(e.arguments?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome, onLabel = { name -> nav.navigate(CollectionRoute.label(name, NativeShell.zone)) })
    }
    composable(CollectionRoute.LABEL, arguments = listOf(str(CollectionRoute.NAME), str(CollectionRoute.ZONE))) { e ->
        val a = e.arguments
        LabelScreen(a?.getString(CollectionRoute.NAME).orEmpty(), a?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome,
            onAlbum = { offset, zone, list -> nav.navigate(ShellRoute.album(offset, zone, "", AlbumLists.put(list), list.index)) })
    }
    composable(CollectionRoute.IMPORT, arguments = listOf(str(CollectionRoute.ZONE))) { e ->
        ImportScreen(e.arguments?.getString(CollectionRoute.ZONE).orEmpty(), client, chrome)
    }
}
