package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.PluginRepos

/*
 * Plugins → Repositories (owner request: a developer's own repositories, to try their plugins): the addresses LMS reads
 * plugins from besides its own, each with Remove, and a box to paste a new one's .xml address. The twin of the web app's
 * Repositories tab (public/lms-plugins.js), in the same words (core's PluginRepos). LMS reads a new repository by itself;
 * its plugins then sit under Available, grouped under the repository's name.
 */

@Composable
internal fun ReposTab(repos: List<String>, busy: Boolean, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val add = { if (!busy && url.isNotBlank()) { onAdd(url); url = "" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(PluginRepos.NOTE, color = paletteColor(Palette.TEXT_DIM), fontSize = 14.sp, lineHeight = 20.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(999.dp)
            BasicTextField(url, { url = it }, Modifier.weight(1f).clip(shape).background(paletteColor(Palette.BG_ELEV_2))
                .border(1.dp, paletteColor(Palette.BORDER), shape).padding(horizontal = 14.dp, vertical = 10.dp),
                textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 14.sp), singleLine = true,
                cursorBrush = SolidColor(paletteColor(Palette.ACCENT)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                decorationBox = { inner -> Box { if (url.isEmpty()) Text(PluginRepos.HINT, color = paletteColor(Palette.TEXT_FAINT), fontSize = 14.sp); inner() } })
            PluginPill(if (busy) "Adding…" else "Add", true, if (busy) Modifier else Modifier.clickable { add() })
        }
        if (repos.isEmpty()) Text(PluginRepos.NONE, color = paletteColor(Palette.TEXT_FAINT), fontSize = 13.sp)
        for (r in repos) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r, Modifier.weight(1f), color = paletteColor(Palette.TEXT), fontSize = 14.sp, fontWeight = FontWeight.W500)
                PluginPill("Remove", false, if (busy) Modifier else Modifier.clickable { onRemove(r) })
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(paletteColor(Palette.BORDER)))
        }
    }
}
