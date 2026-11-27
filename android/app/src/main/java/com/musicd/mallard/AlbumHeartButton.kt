package com.musicd.mallard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.musicd.mallard.client.AlbumHeart
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette

/**
 * The first button on an album's row, before Play Now (item 539; core AlbumHeart): a Qobuz or Tidal album not among your
 * favourites there is a + ([onAdd]: added there, then the heart); otherwise Mandarin's heart, hollow, red once liked
 * ([onLike]). The same ring as ⋯ at the row's other end, so the row is even.
 */
@Composable
internal fun AlbumHeartButton(source: String?, serviceFavourite: Boolean?, favourite: Boolean, onLike: () -> Unit, onAdd: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val ring = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val stadium = RoundedCornerShape(999.dp)
    when (AlbumHeart.of(source, serviceFavourite, favourite)) {
        AlbumHeart.ADD -> OutlinedIconButton(onClick = onAdd, shape = stadium, border = ring, colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = dim),
            modifier = Modifier.parity("album.service-add", Contract.label("album.service-add", 0, AlbumHeart.serviceName(source) ?: "")).size(40.dp, 42.dp)) {
            Icon(painterResource(R.drawable.np_plus), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        else -> {
            val on = favourite
            OutlinedIconButton(onClick = onLike, shape = stadium, border = ring,
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = if (on) paletteColor(Palette.HEART) else dim),
                modifier = Modifier.parity("album.favourite", Contract.label("album.favourite", if (on) 1 else 0)).size(40.dp, 42.dp)) {
                Icon(painterResource(if (on) R.drawable.np_heart_on else R.drawable.np_heart), contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}
