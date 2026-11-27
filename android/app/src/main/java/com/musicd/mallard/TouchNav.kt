package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract

/**
 * The touch layout's row along the foot of the screen, natively
 * (Mandarin's `.touch-nav`): Menu (the side menu over whichever screen is showing), Home, Library and Search, each an
 * equal share of the row, the one the page is on ([lit]: "home", "library", "search") in brass. Each tap is the row's
 * own button's ([onTap], by name), so there is one way to do each thing.
 */
@Composable
internal fun TouchNav(lit: String, onTap: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            NavButton(R.drawable.tn_menu, Contract.label("nav.menu"), false, Modifier.weight(1f).parity("nav.menu", Contract.label("nav.menu")).clickable { onTap("menu") })
            NavButton(R.drawable.tn_home, Contract.label("nav.home"), lit == "home", Modifier.weight(1f).parity("nav.home", Contract.label("nav.home")).clickable { onTap("home") })
            NavButton(R.drawable.tn_library, Contract.label("nav.library"), lit == "library", Modifier.weight(1f).parity("nav.library", Contract.label("nav.library")).clickable { onTap("library") })
            NavButton(R.drawable.tn_search, Contract.label("nav.search"), lit == "search", Modifier.weight(1f).parity("nav.search", Contract.label("nav.search")).clickable { onTap("search") })
        }
    }
}

/** One of the row's buttons: its icon over its word, dim, or brass where the page is. */
@Composable
private fun NavButton(icon: Int, text: String, lit: Boolean, modifier: Modifier) {
    val color = if (lit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.height(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.W600, letterSpacing = 0.02.em, maxLines = 1)
    }
}
