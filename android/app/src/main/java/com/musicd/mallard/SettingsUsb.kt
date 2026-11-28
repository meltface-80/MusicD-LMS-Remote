package com.musicd.mallard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.UsbView
import kotlinx.coroutines.delay

/*
 * Settings → Away from home → USB DAC (owner request: "support for lossless/bitperfect playback via usb dacs, like
 * Mandarin"): Mandarin's USB DAC block, without its DSD. What is on the port, Allow, what its descriptors say it takes,
 * USB direct, fixed volume, the DAC's level and its limit, what it is being fed now, Copy for a report. Read from the
 * app's own driver (UsbDac) every two seconds while shown, at once when the port changes, and after each change here.
 */

/** The USB DAC block under This phone; [info] shows an ⓘ's help. */
@Composable
internal fun UsbBlock(info: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    var usb by remember { mutableStateOf(UsbView(UsbDac.json(context))) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { while (true) { untilOnScreen(); usb = UsbView(UsbDac.json(context)); delay(2_000) } }
    DisposableEffect(Unit) {
        val l: () -> Unit = { activity.runOnUiThread { tick++ } }
        UsbDac.listen(l)
        onDispose { UsbDac.unlisten(l) }
    }
    val again = { tick++ }
    val dim = paletteColor(Palette.TEXT_DIM)
    Text("USB DAC", Modifier.padding(top = 22.dp), color = paletteColor(Palette.TEXT_FAINT), fontSize = 12.sp, letterSpacing = 0.7.sp)
    Block {
        val u = usb
        if (!u.attached) { SetupNote(UsbView.NOTHING, Modifier); return@Block }
        if (u.devices.size > 1) SetupRow("Device", Modifier, null) {
            SelectBox(choiceWords(u.devices, u.chosenDevice), u.devices, Modifier, Modifier.clickable {
                pickChoice(context, u.devices, u.chosenDevice) { k -> Store.setUsbDevice(context, k); UsbDriver.close(); UsbDac.refresh(context); again() }
            })
        }
        SetupRow(u.name, Modifier, null) {
            if (!u.permission) SetupButton("Allow", false, Modifier.clickable { UsbDac.requestPermission(activity); again() })
        }
        if (!u.permission) { SetupNote(UsbView.ASK, Modifier); u.error?.let { Danger(it) }; return@Block }
        if (!u.hasInfo) { SetupNote(u.error ?: UsbView.READING, Modifier); return@Block }
        for ((label, value) in listOf("Class" to u.classLine, "Sample rates" to u.ratesLine, "Bit depth" to u.bitsLine, "Volume" to u.volumeLine) +
            listOfNotNull(u.clock?.let { "Clock now" to it })) {
            SetupRow(label, Modifier, null) { Text(value, color = dim, fontSize = 14.sp) }
        }
        SetupRow("USB direct", Modifier.padding(top = 10.dp), Modifier.clickable { info(UsbView.DIRECT_INFO) }) {
            Toggle(u.direct, Modifier.clickable {
                Store.setUsbDirect(context, !u.direct)
                if (!u.direct) UsbDriver.forgetVolume(context)
                UsbDac.refresh(context); again()
            })
        }
        SetupNote(u.directNote, Modifier)
        if (u.direct) {
            SetupRow("Fixed volume", Modifier.padding(top = 10.dp), null) {
                Toggle(u.fixed, Modifier.clickable { Store.setUsbFixed(context, !u.fixed); UsbDriver.settingsChanged(); again() })
            }
            SetupNote(u.fixedNote, Modifier)
            if (!u.fixed) {
                // The DAC's level, 0 to the limit, as the phone's volume buttons step it too.
                var level by remember(u.volume) { mutableIntStateOf(u.volume) }
                SetupRow("DAC volume", Modifier.padding(top = 10.dp), null) { Text("$level%", color = paletteColor(Palette.TEXT), fontSize = 13.5.sp) }
                LevelTrack(level / 100f, u.limit / 100f) { frac, done -> level = Math.round(frac * 100).coerceIn(0, u.limit); if (done) { UsbDriver.setVolume(level); again() } }
                var limit by remember(u.limit) { mutableIntStateOf(u.limit) }
                SetupRow("Volume limit", Modifier.padding(top = 10.dp), null) { Text("$limit%", color = paletteColor(Palette.TEXT), fontSize = 13.5.sp) }
                // 10 to 100 in fives, the value beside it as it moves, kept once let go.
                LevelTrack((limit - 10) / 90f, 1f) { frac, done -> limit = 10 + Math.round(frac * 18) * 5; if (done) { Store.setUsbLimit(context, limit); UsbDriver.settingsChanged(); again() } }
                SetupNote(UsbView.LIMIT_NOTE, Modifier)
            }
        }
        u.streaming?.let { SetupRow("Streaming now", Modifier.padding(top = 10.dp), null) {}; Text(it, color = dim, fontSize = 13.5.sp) }
        u.streamError?.let { Danger(it) }
        SetupRow("Diagnostics", Modifier.padding(top = 10.dp), null) {
            SetupButton("Copy", false, Modifier.clickable {
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mallard USB DAC", UsbDac.diagnostics(context)))
                }.fold({ setupToast(context, "Copied — paste it into a report") }, { setupToast(context, "Couldn't copy: " + (it.message ?: "")) })
            })
        }
        SetupNote(UsbView.DIAGNOSTICS_NOTE, Modifier)
        u.error?.let { Danger(it) }
    }
}

@Composable
private fun Danger(text: String) { Text(text, Modifier.padding(top = 6.dp), color = paletteColor(Palette.DANGER), fontSize = 12.sp) }

/**
 * A level's track in the accent: the part chosen in the accent, the rest on the raised ground, the 16 thumb at [frac];
 * nothing past [max] can be chosen. [onMove] (the fraction, whether the finger has let go) on a tap or a drag.
 */
@Composable
private fun LevelTrack(frac: Float, max: Float, onMove: (Float, Boolean) -> Unit) {
    val accent = paletteColor(Palette.ACCENT)
    val ground = paletteColor(Palette.BG_ELEV_2)
    var last by remember { mutableStateOf(frac) }
    fun at(x: Float, w: Int) = (x / w).coerceIn(0f, max).also { last = it }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)
            .pointerInput(max) { detectTapGestures { onMove(at(it.x, size.width), true) } }
            .pointerInput(max) { detectHorizontalDragGestures(onDragEnd = { onMove(last, true) }) { change, _ -> onMove(at(change.position.x, size.width), false) } }) {
            val r = 8.dp.toPx(); val h = 4.dp.toPx(); val y = size.height / 2
            val x = r + (size.width - 2 * r) * frac.coerceIn(0f, 1f)
            drawRoundRect(ground, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(accent, Offset(0f, y - h / 2), Size(x, h), CornerRadius(h / 2))
            drawCircle(accent, r, Offset(x, y))
        }
    }
}
