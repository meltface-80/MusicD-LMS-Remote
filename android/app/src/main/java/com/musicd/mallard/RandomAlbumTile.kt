package com.musicd.mallard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Pull down the shade, tap, and a random album starts in the room you were
 * last using. No launcher, no WebView — one request to the server.
 */
class RandomAlbumTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = if (Store.server(this) != null && !Store.offlineMode(this)) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Store.offlineMode(this)) return   // the server's rooms: not in offline mode
        val client = Store.client(this) ?: return
        val tile = qsTile
        tile?.state = Tile.STATE_ACTIVE
        tile?.updateTile()
        Thread({
            val result = runCatching {
                val zone = client.zones().lastZone ?: Store.zone(this)
                client.playRandom(zone)
            }
            result.onFailure { Log.w("RandomAlbumTile", "could not start an album", it) }
            try {
                val t = qsTile ?: return@Thread
                t.state = Tile.STATE_INACTIVE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    t.subtitle = result.getOrNull()?.takeIf { it.isNotBlank() } ?: if (result.isFailure) "Couldn't reach the server" else null
                }
                t.updateTile()
            } catch (e: Exception) {
                // The shade was closed while the request ran; the tile is not listening.
            }
        }, "random-album").start()
    }
}
