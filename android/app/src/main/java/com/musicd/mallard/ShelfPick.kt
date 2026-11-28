package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.ShelfChoices

/**
 * The native Shelf's choices (bug list item 207; docs/specs/screens.json, shelf-choices), as the page's shelf-pick.js
 * draws them on a phone, over the whole screen: Shelf with Reset shelf, how many albums the choices leave, a chip for each
 * choice (× takes it back), the tabs Genres, Artists, Years, Labels, Types and Order (each with how many of it are
 * chosen), a line on the tab with Clear all, the tab's tiles (ShelfTiles.kt), and Show N albums at the foot, which takes
 * the choices away to show the shelf they leave.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShelfPick(shelf: ShelfControl, rem: Dp, client: () -> ServerClient?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = shelf.choices
    val lib = shelf.lib
    shelf.rev   // drawn again whenever a choice moves anything here
    Column(modifier.fillMaxSize().background(paletteColor(Palette.BG)).statusBarsPadding().padding(top = 16.dp, start = rem * 1.2f, end = rem * 1.2f),
        verticalArrangement = Arrangement.spacedBy(rem * 0.75f)) {
        Row(Modifier.fillMaxWidth().padding(top = rem * 3.2f).heightIn(min = rem * 2.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.7f)) {
            Text("Shelf", Modifier.parity("shelf-choices.title").weight(1f), color = paletteColor(Palette.TEXT), fontFamily = youngSerif, fontSize = (rem.value * 1.6f).sp, lineHeight = (rem.value * 1.6f).sp)
            if (c.resetShown()) Box(Modifier.parity("shelf-choices.reset", Contract.label("shelf-choices.reset")).height(rem * 2.2f).clip(CircleShape).border(1.dp, SHELF_BRASS_DIM, CircleShape)
                .clickable { shelf.choose { clearAll() } }.padding(horizontal = rem * 0.95f), contentAlignment = Alignment.Center) {
                Text("Reset shelf", color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value * 0.8f).sp, fontWeight = FontWeight.W700)
            }
        }
        val (before, bold, after) = c.sum(lib, shelf.n, shelf.waiting)
        Text(buildAnnotatedString { append(before); withStyle(SpanStyle(color = paletteColor(Palette.TEXT), fontWeight = FontWeight.W700)) { append(bold) }; append(after) },
            Modifier.parity("shelf-choices.sum"), color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.9f).sp)
        if (lib != null && c.made().isNotEmpty()) {
            val (chips, more) = c.chips(lib)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(rem * 0.4f), verticalArrangement = Arrangement.spacedBy(rem * 0.4f)) {
                for (chip in chips) Row(Modifier.parity("shelf-choices.chip").height(rem * 2f).clip(CircleShape).border(1.dp, SHELF_BRASS_DIM, CircleShape)
                    .background(paletteColor(Palette.ACCENT_SOFT)).padding(start = rem * 0.75f, end = rem * 0.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.3f)) {
                    Text(buildAnnotatedString {
                        if (chip.artists) withStyle(SpanStyle(color = paletteColor(Palette.TEXT_FAINT), fontWeight = FontWeight.W600, fontStyle = FontStyle.Normal)) { append("Artists ") }
                        append(chip.text)
                    }, Modifier.widthIn(max = rem * 16f), color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value * 0.78f).sp, fontWeight = FontWeight.W700, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.parity("shelf-choices.chip-remove", Contract.label("shelf-choices.chip-remove", 0, chip.text)).size(rem * 1.45f).clip(CircleShape)
                        .clickable { shelf.choose { if (chip.cat == "order") toggle("order", "artist") else toggle(chip.cat, chip.id) } }, contentAlignment = Alignment.Center) {
                        Text("×", color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value).sp)
                    }
                }
                if (more > 0) Box(Modifier.parity("shelf-choices.chip-more").height(rem * 2f).clip(CircleShape).border(1.dp, paletteColor(Palette.BORDER), CircleShape)
                    .padding(horizontal = rem * 0.75f), contentAlignment = Alignment.Center) {
                    Text("+$more more", color = paletteColor(Palette.TEXT_DIM), fontSize = (rem.value * 0.78f).sp, fontWeight = FontWeight.W700)
                }
            }
        }
        Tabs(shelf, rem)
        Row(Modifier.fillMaxWidth().heightIn(min = rem * 2.3f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.6f)) {
            Text(c.note(lib), Modifier.parity("shelf-choices.note").weight(1f), color = paletteColor(Palette.TEXT_FAINT), fontSize = (rem.value * 0.82f).sp)
            if (c.clearShown()) Row(Modifier.parity("shelf-choices.clear", Contract.label("shelf-choices.clear")).height(rem * 2.3f).clip(CircleShape).border(1.dp, SHELF_BRASS_DIM, CircleShape)
                .background(paletteColor(Palette.ACCENT_SOFT)).clickable { shelf.choose { clearCat(tab); null } }.padding(start = rem * 0.95f, end = rem * 0.4f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem * 0.45f)) {
                Text("Clear all", color = paletteColor(Palette.ACCENT_TEXT), fontSize = (rem.value * 0.82f).sp, fontWeight = FontWeight.W700)
                Box(Modifier.size(rem * 1.5f).clip(CircleShape).background(paletteColor(Palette.ACCENT)), contentAlignment = Alignment.Center) {
                    Text("×", color = paletteColor(Palette.ON_ACCENT), fontSize = (rem.value).sp)
                }
            }
        }
        ShelfTilesGrid(shelf, c.tab, rem, client, Modifier.fillMaxWidth().weight(1f))
        // a phone's way back to the shelf, saying what it will show
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = rem * 0.7f, bottom = rem * 0.8f)) {
            val (lead, count, tail) = c.done(shelf.n, lib != null)
            Box(Modifier.parity("shelf-choices.done", Contract.label("shelf-choices.done", if (lib == null) 2 else if (shelf.n == 1) 1 else 0, ShelfChoices.fmt(shelf.n)))
                .fillMaxWidth().height(rem * 2.9f).clip(CircleShape).background(paletteColor(Palette.ACCENT)).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
                Text(buildAnnotatedString { append(lead); withStyle(SpanStyle(fontWeight = FontWeight.W800)) { append(count) }; append(tail) },
                    color = paletteColor(Palette.ON_ACCENT), fontSize = (rem.value * 0.92f).sp, fontWeight = FontWeight.W700)
            }
        }
    }
}

/** The tabs (the page's, shelf.html), two even rows of four, each with how many of it are chosen. */
@Composable
private fun Tabs(shelf: ShelfControl, rem: Dp) {
    // drawn again when a tab is picked or a choice moves its badge: the choices' own fields aren't Compose state (item 209:
    // the open tab stayed lifted on Genres)
    shelf.rev
    val c = shelf.choices
    val shape = RoundedCornerShape(rem * 1.6f)
    Column(Modifier.fillMaxWidth().clip(shape).background(paletteColor(Palette.BG_ELEV)).border(1.dp, paletteColor(Palette.BORDER), shape).padding(rem * 0.25f),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        fun pick(id: String) { c.tab = id; shelf.choose { null } }
        fun state(id: String) = if (c.count(id) > 0) 1 else 0
        // The tabs this library has (ShelfChoices.tabs: Types only where something has a kind), in even rows of four.
        for (row in c.tabs(shelf.lib).chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((id, _) in row) TabButton(Modifier.parity("shelf-choices.tab-$id", Contract.label("shelf-choices.tab-$id", state(id), c.count(id).toString())).weight(1f).clickable { pick(id) }, id, c, rem)
            repeat(4 - row.size) { Box(Modifier.weight(1f)) }
        }
    }
}

/** A tab: its name and its count's badge in the brass; the open one lifted, in a brass outline (item 209). */
@Composable
private fun TabButton(modifier: Modifier, id: String, c: ShelfChoices, rem: Dp) {
    val name = ShelfChoices.TABS.first { it.first == id }.second
    val n = c.count(id); val open = c.tab == id
    val ground = if (open) Modifier.background(paletteColor(Palette.BG_ELEV_2), CircleShape).border(1.dp, paletteColor(Palette.ACCENT), CircleShape) else Modifier
    Row(modifier.height(rem * 2.65f).clip(CircleShape).then(ground), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(name, color = paletteColor(if (open) Palette.TEXT else Palette.TEXT_DIM), fontSize = (rem.value * 0.9f).sp, fontWeight = FontWeight.W700, maxLines = 1)
        if (n > 0) Box(Modifier.padding(start = rem * 0.35f).heightIn(min = rem * 1.2f).widthIn(min = rem * 1.2f).clip(CircleShape).background(paletteColor(Palette.ACCENT)).padding(horizontal = rem * 0.3f),
            contentAlignment = Alignment.Center) {
            Text("$n", color = paletteColor(Palette.ON_ACCENT), fontSize = (rem.value * 0.68f).sp, fontWeight = FontWeight.W800)
        }
    }
}
