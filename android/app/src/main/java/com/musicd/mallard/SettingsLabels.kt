package com.musicd.mallard

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.musicd.mallard.client.Choice
import com.musicd.mallard.client.ServerClient
import com.musicd.mallard.client.labelsRescan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Settings → Labels, as the web app's pane: Record labels on or off (the server's), then — kept on the phone, as the
 * page keeps them in its browser — the order a label's albums open in and the fewest albums a label needs to be listed
 * (the Labels screen), and Label data: Force rescan.
 */
private val ORDERS = listOf(Choice("alpha", "Alphabetical"), Choice("random", "Random"))
private val MINIMUMS = listOf(Choice("1", "Show all"), Choice("2", "2 or more"), Choice("5", "5 or more"), Choice("10", "10 or more"))

@Composable
internal fun LabelsPane(client: () -> ServerClient?, onBack: () -> Unit, info: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kept = remember { keptSetting("labels") }
    var on by remember { mutableStateOf(kept?.optBoolean("enabled") ?: false) }
    var count by remember { mutableStateOf(kept?.optInt("count", 0) ?: 0) }
    var known by remember { mutableStateOf(kept != null) }
    var order by remember { mutableStateOf(Store.labelOrder(context)) }
    var min by remember { mutableStateOf(Store.labelMin(context).toString()) }
    var rescanning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { readSetting(client, "labels")?.let { on = it.optBoolean("enabled"); count = it.optInt("count", 0); known = true } }
    SettingsFrame {
        PaneHead("Labels", onBack)
        Block {
            SetupRow("Record labels", Modifier, Modifier.clickable { info("Off by default. When on, the app works out each album's record label and fetches label logos, which is the only part of the app that talks to Qobuz's public pages and MusicBrainz for this. When off none of it runs: no scan, no lookups, and labels are left out of search results entirely.") }) {
                Toggle(on, Modifier.clickable {
                    val was = on; on = !on
                    saveSetting(scope, context, client, "labels", JSONObject().put("enabled", on), if (on) "Labels on — the first scan runs in the background" else "Labels off", undo = { on = was })
                }, known)
            }
            SetupNote(if (on && count > 0) "$count labels found so far." else "Off by default. Everything below applies only while this is on.", Modifier)
        }
        Block {
            SetupRow("Label album order", Modifier, Modifier.clickable { info("When you open a record label, list its albums alphabetically or shuffle them.") }) {
                SelectBox(choiceWords(ORDERS, order), ORDERS, Modifier, Modifier.clickable { pickChoice(context, ORDERS, order) { v -> order = v; Store.setLabelOrder(context, v) } })
            }
            SetupRow("Minimum albums per label", Modifier, Modifier.clickable { info("Hide labels that have fewer albums than this threshold. Useful if the scanner picks up one-off compilations from unexpected labels.") }) {
                SelectBox(choiceWords(MINIMUMS, min), MINIMUMS, Modifier, Modifier.clickable { pickChoice(context, MINIMUMS, min) { v -> min = v; Store.setLabelMin(context, v.toInt()) } })
            }
        }
        Block {
            SetupRow("Label data", Modifier, Modifier.clickable { info("Force a complete rescan of all label sources (iTunes, MusicBrainz, Discogs, file tags). Use this after importing new music or if label data looks wrong. This clears the label name cache — logos and MBIDs are kept.") }) {
                SetupButton("Force rescan", rescanning, Modifier.clickable(enabled = !rescanning && on) {
                    val c = client() ?: return@clickable
                    rescanning = true
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { runCatching { c.labelsRescan(true) }.getOrDefault(false) }
                        setupToast(context, if (started) "Rescan started — labels fill in over the next few minutes" else "A scan is already running")
                        rescanning = false
                    }
                })
            }
        }
    }
}
