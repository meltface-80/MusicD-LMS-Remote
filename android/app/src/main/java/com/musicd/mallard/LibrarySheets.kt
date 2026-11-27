package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Facets
import com.musicd.mallard.client.AlbumSummary
import com.musicd.mallard.client.LibraryView
import com.musicd.mallard.client.LocalView
import com.musicd.mallard.client.Palette

/*
 * The Library wall's sheets (bug list item 52; the page's openLibSheet,
 * library-focus.js and its sort sheet; style.css .lib-sheet):
 * a panel up from the foot of the screen over a dimmed page, its title and
 * ✕ at the top, its body scrolling, its buttons along the foot. Focus: the
 * listening, then each facet as a section that opens to its chips, a chip
 * tapped once included, again excluded, once more cleared; Clear all and
 * Show albums. Sort: a row
 * each way, the one on in brass with its arrow, tapped again to reverse
 * (Random reshuffles). Music on device's sheets (item 57) are the same, over
 * the phone's own facets and sorts (LocalView).
 */

/** The panel: [head] (its title and close) at the top, [body] scrolling, [foot] along its bottom; a tap on the dimmed page closes it too (Material's sheet does the dimming and the drag). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetPanel(onClose: () -> Unit, head: @Composable RowScope.() -> Unit, foot: (@Composable RowScope.() -> Unit)?, body: @Composable ColumnScope.() -> Unit) {
    val hairline = Color(0x12FFFFFF)   // the page's --panel-hairline, white at .07
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82).dp
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = null,
        shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant, scrimColor = Color(0x8C000000)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = head)
            HorizontalDivider(color = hairline)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp), content = body)
            if (foot != null) {
                HorizontalDivider(color = hairline)
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = foot)
            }
        }
    }
}

/** A sheet's title (16, bold), [modifier] carrying its key. */
@Composable
internal fun SheetTitle(title: String, modifier: Modifier) {
    Text(title, modifier, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.W700)
}

/** A sheet's ✕ (the page's .icon-btn, 40 round), [modifier] carrying its key and its tap. */
@Composable
internal fun SheetClose(modifier: Modifier) {
    Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, lineHeight = 22.sp)
    }
}

/** The Library's sheets: [title] and the close keyed as its contract has them. */
@Composable
private fun Sheet(title: String, onClose: () -> Unit, foot: (@Composable RowScope.() -> Unit)?, body: @Composable ColumnScope.() -> Unit) {
    SheetPanel(onClose, head = {
        SheetTitle(title, Modifier.parity("library.focus-title library.sort-title"))
        SheetClose(Modifier.parity("library.focus-close library.sort-close", Contract.label("library.focus-close")).clip(CircleShape).clickable(onClick = onClose))
    }, foot, body)
}

/** A sheet's buttons along the foot (.action-btn): their colours, [primary] in brass, and the plain ones' edge. */
@Composable
internal fun footColors(primary: Boolean) = if (primary) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    else ButtonDefaults.buttonColors(containerColor = paletteColor(Palette.BG_ELEV_2), contentColor = MaterialTheme.colorScheme.onSurface)

/** Why a facet's chips don't add up to the library (the page's COVERAGE_NOTE). */
private val COVERAGE_NOTE = mapOf(
    "decade" to "Release years come from your file tags (ORIGINALDATE, then DATE) and from MusicBrainz. Undated albums aren't in any decade.",
    "genre" to "Genres are read from your files' GENRE tags. An album with no genre tag won't appear here.",
    "format" to "Read from your own files.",
    "added" to "Date added is when the files arrived in your music folder, as Mandarin could work out for itself — file timestamps, and albums appearing between scans."
)
private val COVERAGE_OF = mapOf("rate" to "format", "bits" to "format", "chan" to "format")

/** The Focus sheet over [view] with [facets] (null until they come); each change [onChange]; [onShow] closes with the view applied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusSheet(view: LibraryView, facets: Facets?, onChange: (LibraryView) -> Unit, onShow: () -> Unit, onClose: () -> Unit) {
    // Which sections are open: a section with something on opens by itself, and stays open until its head is tapped.
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val faint = paletteColor(Palette.TEXT_FAINT)
    Sheet("Focus", onClose, foot = {
        val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, ButtonDefaults.shape)
        val pad = PaddingValues(horizontal = 8.dp)
        Button(onClick = { onChange(view.cleared()); onClose() }, colors = footColors(false), contentPadding = pad,
            modifier = Modifier.parity("library.focus-clear", Contract.label("library.focus-clear")).weight(1f).defaultMinSize(minHeight = 40.dp).then(edge)) {
            Text(Contract.label("library.focus-clear"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
        Button(onClick = onShow, colors = footColors(true), contentPadding = pad,
            modifier = Modifier.parity("library.focus-show", Contract.label("library.focus-show")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("library.focus-show"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        @Composable fun Section(id: String, label: String, active: Int, chips: @Composable () -> Unit, notes: List<String>) =
            FocusSection(id, label, active, open, chips, notes)
        @Composable fun Chip(label: String, state: String, onTap: () -> Unit) = FocusChip(label, state, onTap)
        // Listening first: the one facet always there, from this server's own play history.
        Section("played", "Listening", if (view.played != "any") 1 else 0, chips = {
            for (p in LibraryView.PLAYED) Chip(p.label, if (view.played == p.id) "on" else "off") { onChange(view.copy(played = p.id)) }
        }, notes = if (facets != null && !facets.hasPlays) listOf("Mandarin hasn't seen anything play yet, so these use an empty history — everything counts as never played.") else emptyList())
        for (f in facets?.facets ?: emptyList()) {
            if (f.id !in LibraryView.FACET_IDS) continue
            val sel = view.facets[f.id] ?: emptyList()
            val notes = ArrayList<String>()
            if (f.totalValues > f.values.size) notes += "Showing the ${f.values.size} most common of ${f.totalValues}."
            val cov = COVERAGE_OF[f.id] ?: f.id
            val have = facets?.coverage?.get(cov)
            if (COVERAGE_NOTE[cov] != null && have != null && facets != null && facets.total > 0 && have < facets.total) notes += "$have of ${facets.total} albums. " + COVERAGE_NOTE[cov]
            if (f.id == "source" && facets?.sourcesDerived == true) notes += "Every album in your library came from your own files."
            Section(f.id, f.label, sel.size, chips = {
                for (v in f.values) Chip(v.label + " (" + v.count + ")", view.state(f.id, v.value)) { onChange(view.cycle(f.id, v.value)) }
                // Anything selected the server didn't send back (a value past the commonest): a chip of its own, so it can be cleared.
                val listed = f.values.map { it.value }
                for (raw in sel) { val value = raw.removePrefix("!"); if (value !in listed) Chip(value, view.state(f.id, value)) { onChange(view.cycle(f.id, value)) } }
            }, notes = notes)
        }
        Text("Tap a filter once to include it, again to exclude it, once more to clear it. Everything here is read from your files' own tags.",
            Modifier.parity("library.focus-help").padding(top = 16.dp), color = faint, fontSize = 11.5.sp, lineHeight = 17.sp)
    }
}

/** A facet of the Focus sheet as a section: its head (the name, how many of it are on, the caret), open to its [chips] and [notes]; a section with something on opens by itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusSection(id: String, label: String, active: Int, open: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>, chips: @Composable () -> Unit, notes: List<String>) {
    val faint = paletteColor(Palette.TEXT_FAINT)
    if (open[id] == null && active > 0) open[id] = true
    val expanded = open[id] == true
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Color(0x12FFFFFF))
        Row(Modifier.parity("library.focus-section", Contract.label("library.focus-section", 0, label)).fillMaxWidth().clickable { open[id] = !expanded }.padding(vertical = 13.dp, horizontal = 2.dp)
            .defaultMinSize(minHeight = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), Modifier.weight(1f), color = if (expanded) MaterialTheme.colorScheme.onSurface else faint, fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.W700, letterSpacing = 0.14.em)
            if (active > 0) Text("$active", Modifier.defaultMinSize(minWidth = 18.dp).height(18.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp),
                color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.W700, lineHeight = 18.sp)
            Text(if (expanded) "⌃" else "⌄", color = faint, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (expanded) {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { chips() }
            for (n in notes) Text(n, Modifier.parity("library.focus-note").padding(top = 8.dp, bottom = 4.dp), color = faint, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
    }
}

/** A value's chip: plain, on (in brass), or excluded (struck through, in red). */
@Composable
private fun FocusChip(label: String, state: String, onTap: () -> Unit) {
    val on = state == "on"; val not = state == "not"
    Box(Modifier.parity("library.focus-chip", Contract.label("library.focus-chip", if (not) 1 else 0, label)).clip(RoundedCornerShape(999.dp))
        .background(if (on) paletteColor(Palette.ACCENT_SOFT) else if (not) Color(0x29E8836B) else paletteColor(Palette.BG_ELEV_2))
        .border(1.dp, if (on) MaterialTheme.colorScheme.primary else if (not) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
        .clickable(onClick = onTap).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (on || not) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600,
            textDecoration = if (not) TextDecoration.LineThrough else null)
    }
}

/** Music on device's Focus (the page's openLocalFocusSheet): the phone's facets, each value with its count; Clear all and Show albums along the foot. */
@Composable
internal fun LocalFocusSheet(view: LocalView, albums: List<AlbumSummary>, onChange: (LocalView) -> Unit, onClose: () -> Unit) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    Sheet("Focus", onClose, foot = {
        val edge = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, ButtonDefaults.shape)
        val pad = PaddingValues(horizontal = 8.dp)
        Button(onClick = { onChange(view.cleared()); onClose() }, colors = footColors(false), contentPadding = pad,
            modifier = Modifier.parity("library.focus-clear", Contract.label("library.focus-clear")).weight(1f).defaultMinSize(minHeight = 40.dp).then(edge)) {
            Text(Contract.label("library.focus-clear"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
        Button(onClick = onClose, colors = footColors(true), contentPadding = pad,
            modifier = Modifier.parity("library.focus-show", Contract.label("library.focus-show")).weight(1f).defaultMinSize(minHeight = 40.dp)) {
            Text(Contract.label("library.focus-show"), fontSize = 13.sp, fontWeight = FontWeight.W500, maxLines = 1)
        }
    }) {
        for ((id, label) in LocalView.FACETS) {
            val sel = view.facets[id] ?: emptyList()
            val counts = LinkedHashMap<String, Int>()
            for (a in albums) { val v = LocalView.facetOf(id, a); counts[v] = (counts[v] ?: 0) + 1 }
            // The page's order: decades newest first, the rest by name.
            val values = if (id == "decade") counts.keys.sortedDescending() else counts.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
            FocusSection(id, label, sel.size, open, chips = {
                for (v in values) FocusChip(v + " (" + counts[v] + ")", view.state(id, v)) { onChange(view.cycle(id, v)) }
            }, notes = emptyList())
        }
    }
}

/** The Sort sheet: a row each way; the one on in brass with its arrow, tapped again to reverse (Random: to reshuffle). */
@Composable
internal fun SortSheet(view: LibraryView, onChoose: (String) -> Unit, onClose: () -> Unit) {
    Sheet("Sort by", onClose, foot = null) { SortRows(LibraryView.SORTS, view.sort, view.dir, view.dirLabel, onChoose) }
}

/** Music on device's Sort (the page's openLocalSortSheet): the same rows over the phone's own ways. */
@Composable
internal fun LocalSortSheet(view: LocalView, onChoose: (String) -> Unit, onClose: () -> Unit) {
    Sheet("Sort by", onClose, foot = null) { SortRows(LocalView.SORTS, view.sort, view.dir, view.dirLabel, onChoose) }
}

/** The Sort sheet's rows: [sorts], the one [sort] on in brass with its arrow for [dir]. */
@Composable
private fun SortRows(sorts: List<LibraryView.Sort>, sort: String, dir: String, dirLabel: String, onChoose: (String) -> Unit) {
    for (o in sorts) {
        val on = sort == o.id
        Row(Modifier.parity("library.sort-row", Contract.label("library.sort-row", if (on && o.hasDir) 1 else 0, o.label, dirLabel)).fillMaxWidth().padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(if (on) paletteColor(Palette.ACCENT_SOFT) else Color.Transparent).clickable { onChoose(o.id) }.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (on && o.hasDir) (if (dir == "desc") "↓" else "↑") else "", Modifier.width(20.dp), color = MaterialTheme.colorScheme.secondary, fontSize = 19.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            Column {
                Text(o.label, color = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (on) FontWeight.W700 else FontWeight.W600)
                o.note?.let { Text(it, Modifier.padding(top = 2.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 11.5.sp, lineHeight = 15.sp) }
            }
        }
    }
}

/**
 * The Focus and Sort pills at the top right of the wall (the touch layout's; style.css `.library-controls.sub-bar
 * .lib-ctl`): brass pills 32 high with the words in the charcoal, "Focus" with its count inverted when filters are on,
 * "Sort ⌄"; each a tap to its sheet.
 */
@Composable
internal fun BoxScope.LibraryPills(focus: Int, onFocus: () -> Unit, onSort: () -> Unit) {
    val pill = Modifier.height(32.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary)
    Row(Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 12.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.parity("library.focus", if (focus == 0) Contract.label("library.focus") else Contract.label("library.focus", if (focus == 1) 1 else 2, focus.toString())).then(pill)
            .clickable(onClick = onFocus).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Focus", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600)
            if (focus > 0) Text("$focus", Modifier.defaultMinSize(minWidth = 16.dp).height(16.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.onPrimary).padding(horizontal = 5.dp),
                color = MaterialTheme.colorScheme.primary, fontSize = 10.5.sp, fontWeight = FontWeight.W700, lineHeight = 16.sp)
        }
        Row(Modifier.parity("library.sort", Contract.label("library.sort", 0, "")).then(pill).clickable(onClick = onSort).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Sort", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600)
            Text("⌄", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, lineHeight = 13.sp, fontWeight = FontWeight.W600)
        }
    }
}
