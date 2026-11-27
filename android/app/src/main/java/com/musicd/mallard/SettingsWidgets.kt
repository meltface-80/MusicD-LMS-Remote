package com.musicd.mallard

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.SetupSettings
import com.musicd.mallard.client.WallPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* Settings' widgets and their plumbing, Mandarin's own: rows, switches, lists, buttons and the ⓘ's help. */

internal fun choiceWords(choices: List<Choice>, value: String): String = Choice.words(choices, value)

internal fun setupToast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** The phone's own list of [choices], as the WebView put up for a page's list: [current] ticked, [onPick] with the one tapped. */
internal fun pickChoice(context: Context, choices: List<Choice>, current: String, onPick: (String) -> Unit) {
    AlertDialog.Builder(context).setSingleChoiceItems(choices.map { it.words }.toTypedArray(), choices.indexOfFirst { it.value == current }) { d, i ->
        d.dismiss(); if (choices[i].value != current) onPick(choices[i].value)
    }.show()
}

/** One of the server's settings as last read ([name]), for a page's first frame (item 198); none if never read. */
internal fun keptSetting(name: String): JSONObject? = com.musicd.mallard.client.SettingsKept.last("/api/settings/$name")

/** One of the server's settings as it has it ([name]); none when it can't be reached. */
internal suspend fun readSetting(client: () -> ServerClient?, name: String): JSONObject? {
    val c = client() ?: return null
    return withContext(Dispatchers.IO) { runCatching { c.setting(name) }.getOrNull() }
}

/** A change to one of the server's settings saved: [said] once it is, [undo] and what went wrong if not; [then] with the answer. */
internal fun saveSetting(scope: CoroutineScope, context: Context, client: () -> ServerClient?, name: String, body: JSONObject, said: String?, undo: () -> Unit,
                        failed: String = "Couldn't save: ", then: (JSONObject) -> Unit = {}) {
    val c = client() ?: return
    scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { c.saveSetting(name, body).also { if (it.optString("error").isNotEmpty()) throw IllegalStateException(it.optString("error")) } } }
        r.onSuccess { said?.let { s -> setupToast(context, s) }; then(it) }.onFailure { undo(); setupToast(context, failed + (com.musicd.mallard.client.Failure.said(it, "no answer"))) }
    }
}

/** This phone's screen settings as the app keeps them (core ScreenPrefs). */
internal fun pagePrefs(context: Context): WallPrefs = WallPrefs.parse(NativeShell.prefs ?: Store.homePrefs(context))

/** A screen setting ([name], as the page's prefs name it) changed here: kept by the app (item 111); the settings now. */
internal fun setPagePref(context: Context, name: String, value: String): WallPrefs {
    if (NativeShell.prefs == null) NativeShell.prefs = Store.homePrefs(context)
    NativeShell.keep(name, value)
    return WallPrefs.parse(NativeShell.prefs)
}



/** A block of rows (.settings-block): 4 over and under. */
@Composable
internal fun Block(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
}

/** A setting's row (.settings-row): its name in 16 (and its ⓘ, [info]: its key and tap) on the left, [control] on the right, 16 apart. */
@Composable
internal fun SetupRow(name: String, nameMod: Modifier, info: Modifier?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f).heightIn(min = if (info != null) 32.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, nameMod.weight(1f, fill = false), color = paletteColor(Palette.TEXT), fontSize = 16.sp, lineHeight = 22.sp)
            if (info != null) Box(info.size(32.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
                Text("ⓘ", color = paletteColor(Palette.TEXT_FAINT), fontSize = 16.sp, lineHeight = 16.sp)
            }
        }
        control()
    }
}

/** A note under a setting (.settings-note): 12 faint in 1.45, 6 over it. */
@Composable
internal fun SetupNote(text: String, modifier: Modifier) {
    Text(text, modifier.padding(top = 6.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, lineHeight = 17.4.sp)
}

/** A switch (.switch): 46 by 28, its 22 thumb dim on the raised ground, or white on the accent once on; its room alone while not [known] (item 198). */
@Composable
internal fun Toggle(on: Boolean, modifier: Modifier, known: Boolean = true) {
    if (!known) { Box(Modifier.size(46.dp, 28.dp)); return }
    Box(modifier.size(46.dp, 28.dp).clip(CircleShape).background(paletteColor(if (on) Palette.ACCENT else Palette.BG_ELEV_2))
        .border(1.dp, if (on) Color.Transparent else paletteColor(Palette.BORDER), CircleShape).padding(horizontal = 3.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = if (on) 18.dp else 0.dp).size(22.dp).background(if (on) Color.White else paletteColor(Palette.TEXT_DIM), CircleShape))
    }
}

/**
 * A list (.settings-select): its choice in 15 on the raised ground, 12 round, 9 and 16 in (34 at the right, where its
 * caret is: [caret], its key, or none), as wide as its longest choice and 110 at least, or [width].
 */
@Composable
internal fun SelectBox(words: String, choices: List<Choice>, caret: Modifier?, modifier: Modifier, width: Dp? = null) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 15.sp, lineHeight = 19.sp)
    val longest = choices.maxOfOrNull { measurer.measure(it.words, style).size.width } ?: 0
    val w = width ?: with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(110.dp, longest.toDp() + 50.dp) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.width(w).height(37.dp).clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape), contentAlignment = Alignment.CenterStart) {
        Text(words, Modifier.padding(start = 16.dp, end = 34.dp), color = paletteColor(Palette.TEXT), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1)
        if (caret != null) Icon(painterResource(R.drawable.st_caret), contentDescription = null, tint = paletteColor(Palette.TEXT_DIM),
            modifier = caret.align(Alignment.CenterEnd).padding(end = 12.dp).size(16.dp))
    }
}

/** A button of a setting's (.settings-update-btn): 36 tall, 14 in, 8 round, 13 dim; dim while it works. */
@Composable
internal fun SetupButton(words: String, busy: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 36.dp).alpha(if (busy) 0.6f else 1f).clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(words, color = paletteColor(Palette.TEXT_DIM), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

/** The range's track (the page's in the accent): the part chosen in the accent, the rest light, the 16 thumb at [frac]. */
@Composable
internal fun RangeTrack(frac: Float, modifier: Modifier) {
    val accent = paletteColor(Palette.ACCENT)
    Canvas(modifier) {
        val r = 8.dp.toPx(); val h = 4.dp.toPx(); val y = size.height / 2
        val x = r + (size.width - 2 * r) * frac.coerceIn(0f, 1f)
        drawRoundRect(Color(0xFFEFEFEF), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(accent, Offset(0f, y - h / 2), Size(x, h), CornerRadius(h / 2))
        drawCircle(accent, r, Offset(x, y))
    }
}

/** The ⓘ's help (.settings-info-toast): along the foot, over the page, until it has been read. */
@Composable
internal fun BoxScope.InfoTip(text: String, modifier: Modifier) {
    val box = RoundedCornerShape(12.dp)
    Box(modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp).widthIn(max = minOf(400, (LocalConfiguration.current.screenWidthDp * 0.92f).toInt()).dp)
        .clip(box).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), box).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(text, color = paletteColor(Palette.TEXT), fontSize = 14.sp, lineHeight = 21.sp)
    }
}
