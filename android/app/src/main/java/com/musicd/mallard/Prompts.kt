package com.musicd.mallard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Palette

/**
 * A name asked for (the web app's window.prompt, in Mallard's look): [prompt] over one field, [initial] in it, OK and
 * Cancel. A blank name is [onOk]'s to refuse; the web's prompt hands it back too.
 */
@Composable
internal fun NameDialog(prompt: String, initial: String = "", ok: String = "OK", onCancel: () -> Unit, onOk: (String) -> Unit) {
    var typed by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onCancel, containerColor = paletteColor(Palette.BG_ELEV),
        title = { Text(prompt, color = MaterialTheme.colorScheme.onSurface, fontFamily = youngSerif, fontSize = 20.sp) },
        text = {
            val shape = RoundedCornerShape(12.dp)
            BasicTextField(typed, { typed = it }, Modifier.fillMaxWidth().clip(shape).background(paletteColor(Palette.BG_ELEV_2)).border(1.dp, paletteColor(Palette.BORDER), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp), singleLine = true, textStyle = TextStyle(color = paletteColor(Palette.TEXT), fontSize = 16.sp),
                cursorBrush = SolidColor(paletteColor(Palette.ACCENT)), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onOk(typed.trim()) }))
        },
        confirmButton = { TextButton(onClick = { onOk(typed.trim()) }) { Text(ok, color = MaterialTheme.colorScheme.secondary) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) } })
}
