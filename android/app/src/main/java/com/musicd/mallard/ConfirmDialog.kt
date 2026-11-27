package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Palette

/**
 * The page's yes-or-no sheet, natively (docs/specs/screens.json, confirm),
 * drawn as the page draws it (style.css, .confirm-box): a box of 340 on the
 * raised ground with its edge and corners of 18, the question at 16px in
 * the middle, No and Yes as two 44s side by side, Yes in brass; the screen
 * behind it under a veil. A tap outside is a No, as on the page.
 */
@Composable
internal fun ConfirmDialog(message: String, onAnswer: (Boolean) -> Unit) {
    Dialog(onDismissRequest = { onAnswer(false) }) {
        // The veil over the screen at the page's depth (its backdrop, black at .55): the window's own, set so.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.55f) }
        val box = RoundedCornerShape(18.dp)
        Box(contentAlignment = Alignment.Center) {
            Column(Modifier.width(340.dp).clip(box).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outline, box)
                .padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 16.dp)) {
                Text(message, Modifier.parity("confirm.message").fillMaxWidth().padding(bottom = 18.dp), color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp, lineHeight = 22.4.sp, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val button = RoundedCornerShape(12.dp)
                    Box(Modifier.parity("confirm.no", Contract.label("confirm.no")).weight(1f).height(44.dp).clip(button).background(paletteColor(Palette.BG_ELEV_2))
                        .border(1.dp, MaterialTheme.colorScheme.outline, button).clickable { onAnswer(false) }, contentAlignment = Alignment.Center) {
                        Text(Contract.label("confirm.no"), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                    }
                    Box(Modifier.parity("confirm.yes", Contract.label("confirm.yes")).weight(1f).height(44.dp).clip(button).background(MaterialTheme.colorScheme.primary)
                        .clickable { onAnswer(true) }, contentAlignment = Alignment.Center) {
                        Text(Contract.label("confirm.yes"), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}
