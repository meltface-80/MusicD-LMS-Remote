package com.musicd.mallard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musicd.mallard.client.Contract
import com.musicd.mallard.client.Credit
import com.musicd.mallard.client.Mqa
import com.musicd.mallard.client.Palette
import com.musicd.mallard.client.ServerClient
import kotlinx.coroutines.launch
import com.musicd.mallard.client.NowAlbum
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

/**
 * Now playing, native (stage N2 of old/plan-android-native.md, a
 * preview: Settings → Setup → UI Settings, on this phone), drawn as the
 * page's (style.css, `.np-screen` and the hifi palette): the brass corner
 * discs and the tabs, the cover whole in the room the controls leave, the
 * track, its artists and album, the format badge, the level meter, the
 * transport (brass, play/pause a disc) and, under it, Play on and the volume;
 * the row along the foot under them all (item 296), Menu first, nothing lit.
 * The room is followed as the notification follows it ([followRoom]).
 */
@Composable
internal fun NowPlayingScreen(zoneId: String, client: () -> ServerClient?, onBack: () -> Unit, onRoom: (String) -> Unit, onPage: (String, String?) -> Unit, onNav: (String) -> Unit) {
    val room = followRoom(zoneId, client)
    val view = room.view
    val answered = room.answered
    var queueTab by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var signalOpen by remember { mutableStateOf(false) }   // the signal path sheet (item 247)
    var sharing by remember { mutableStateOf(false) }   // its Share card open (item 75)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val cover = rememberCover(view?.imageKey, 1000, client)

    // A command to the room; the answer it waits on shows what it did.
    fun act(block: (ServerClient) -> Unit) {
        val c = client() ?: return
        scope.sendTap(context, c, block)
    }

    val white = paletteColor(Palette.MODAL_TEXT)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val brass = MaterialTheme.colorScheme.primary
    val disc = IconButtonDefaults.filledIconButtonColors(containerColor = brass, contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = brass, disabledContentColor = MaterialTheme.colorScheme.onPrimary)
    // Each item is the screens contract's (docs/specs/screens.json), keyed as
    // the page's is: tools/quality/parity.js holds this screen to the page's.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The row along the foot under it all (item 296), as on every screen: the cover, sized to what is left, is smaller by it.
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // The corner discs and, between them, the tabs; the discs and the tabs are the Queue's too (docs/specs/screens.json, queue).
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    FilledIconButton(onClick = onBack, colors = disc, modifier = Modifier.parity("now-playing.back queue.back", Contract.label("now-playing.back")).size(40.dp).align(Alignment.TopStart)) {
                        Icon(painterResource(R.drawable.np_back), contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                    Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        NpTab(Contract.label("now-playing.tab-now-playing"), !queueTab, Modifier.parity("now-playing.tab-now-playing queue.tab-now-playing", Contract.label("now-playing.tab-now-playing")).clickable(role = Role.Tab) { queueTab = false })
                        NpTab(Contract.label("now-playing.tab-queue"), queueTab, Modifier.parity("now-playing.tab-queue queue.tab-queue", Contract.label("now-playing.tab-queue")).clickable(role = Role.Tab) { queueTab = true })
                    }
                    FilledIconButton(onClick = { sharing = true }, enabled = view != null, colors = disc,
                        modifier = Modifier.parity("now-playing.share queue.share", Contract.label("now-playing.share")).size(40.dp).align(Alignment.TopEnd)) {
                        Icon(painterResource(R.drawable.np_share), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                // The volume sheet, at the foot of the screen as the page's is (24px up), over whichever tab is on.
                VolumeSheet(volumeOpen, { volumeOpen = false }, zoneId, view?.volume, ::act, above = false, inset = 24.dp)
                // The Share card of what plays, natively (item 75): the mini player is hidden here, so no room is kept for it.
                if (sharing) view?.let { ShareSheet(it.album, it.artist, it.imageKey, 0.dp, client) { sharing = false } }
                if (queueTab) {
                    QueueScreen(zoneId, client, changed = view?.answeredAtMs, modifier = Modifier.weight(1f).padding(horizontal = 18.dp))
                    return@Column
                }
                val v = view
                if (v == null) {
                    Spacer(Modifier.weight(1f))
                    Text(if (answered) "Nothing is playing here" else "", Modifier.parity("now-playing.track"), style = MaterialTheme.typography.bodyLarge, color = dim)
                    Spacer(Modifier.weight(1f))
                    return@Column
                }
                // The cover whole: a framed square at the size the room left for it, never bled to the edges.
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.parity("now-playing.cover").aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, paletteColor(Palette.COVER_RING), RoundedCornerShape(12.dp))) {
                        cover?.let { Image(it, contentDescription = v.album, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                Column(Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The page's lines: the track at 27px in 1.15, its artists at 18 and the album at 14 in their own, the badge's 12 in 1.2 (Type.kt).
                    Text(v.title, Modifier.parity("now-playing.track").padding(top = 6.dp).lineBox(27.sp, 31.05.sp), fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 31.05.sp, letterSpacing = (-0.01).em,
                        color = white, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    // The artists as the page links them, in the page's quiet white: each with a page of their own a tap away, the rest plain.
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                        val credits = v.artists.ifEmpty { listOf(Credit(v.artist, false)) }
                        credits.forEachIndexed { i, c ->
                            if (i > 0) Text(" · ", Modifier.parity("now-playing.artist"), fontSize = 18.sp, lineHeight = 24.sp, color = dim, maxLines = 1)
                            if (c.linkable) Text(c.name, Modifier.parity("now-playing.artist", Contract.label("now-playing.artist", 0, c.name)).clickable { onPage("artist", c.name) },
                                fontSize = 18.sp, lineHeight = 24.sp, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Text(c.name, Modifier.parity("now-playing.artist"), fontSize = 18.sp, lineHeight = 24.sp, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(v.album, Modifier.parity("now-playing.album", Contract.label("now-playing.album", 0, v.album)).padding(top = 3.dp).clickable {
                        // The phone's own album, playing on the phone, is the phone's page (item 197): the library has never seen it.
                        v.phoneAlbum?.let { onPage("phone-album", it); return@clickable }
                        // Its album found as the page finds it (item 111): by the playing track, else the library searched by its name.
                        scope.launch { findNowAlbum(client, zoneId, v.album, v.artist)?.let { onPage("album", it.toString()) } ?: Toast.makeText(context, NowAlbum.NOT_INDEXED, Toast.LENGTH_SHORT).show() } },
                        fontSize = 14.sp, lineHeight = 19.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // What the room is hearing, every step of the way (item 247): the lines centred, a tap for the signal path.
                    if (v.infoLines.isNotEmpty()) SignalLines(v.infoLines, v.signal?.let { { signalOpen = true } }, v.signal?.alert)
                    v.signal?.takeIf { signalOpen }?.let { SignalPathSheet(it, { signalOpen = false }) }
                    // MQA (item 418): its badge, and where this room sends it untouched to a DAC that decodes it, that it does.
                    v.mqa?.let { m ->
                        val c = if (m.studio) MaterialTheme.colorScheme.primary else dim
                        Text(m.label, Modifier.parity("now-playing.mqa").padding(top = 8.dp).border(1.dp, if (m.studio) c else paletteColor(Palette.BORDER), RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 1.dp), color = c, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.W600)
                        if (m.untouched) Text(Mqa.UNTOUCHED, Modifier.parity("now-playing.mqa-untouched").padding(top = 4.dp), color = dim, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.W500)
                    }
                    RoomSeekBar(room, v, client) { to -> act { it.seek(zoneId, to) } }
                    // The transport: shuffle and repeat smaller and dimmer until on, previous and next in brass, play/pause a brass disc.
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                        val lit = MaterialTheme.colorScheme.secondary
                        val soft = paletteColor(Palette.ACCENT_SOFT)
                        // Their wash when on is the button's own colour, not a background modifier: that lay outside the 48 touch target, and drew 48 wide.
                        IconButton(onClick = { act { it.zoneSettings(zoneId, shuffle = !v.shuffle) } }, colors = IconButtonDefaults.iconButtonColors(containerColor = if (v.shuffle) soft else Color.Transparent, contentColor = if (v.shuffle) lit else dim),
                            modifier = Modifier.parity("now-playing.shuffle", Contract.label("now-playing.shuffle", v.shuffleState)).size(40.dp)) {
                            Icon(painterResource(R.drawable.np_shuffle), contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { act { it.control(zoneId, "previous") } }, enabled = v.canPrevious,
                            modifier = Modifier.parity("now-playing.previous", Contract.label("now-playing.previous")).size(56.dp)) {
                            Icon(painterResource(R.drawable.np_previous), contentDescription = null, tint = if (v.canPrevious) brass else paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(30.dp))
                        }
                        FilledIconButton(onClick = { act { it.control(zoneId, "playpause") } }, shape = CircleShape, colors = disc,
                            modifier = Modifier.parity("now-playing.playpause", Contract.label("now-playing.playpause", if (v.playing) 1 else 0)).size(68.dp)) {
                            Icon(painterResource(if (v.playing) R.drawable.np_pause else R.drawable.np_play), contentDescription = null, modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = { act { it.control(zoneId, "next") } }, enabled = v.canNext,
                            modifier = Modifier.parity("now-playing.next", Contract.label("now-playing.next")).size(56.dp)) {
                            Icon(painterResource(R.drawable.np_next), contentDescription = null, tint = if (v.canNext) brass else paletteColor(Palette.TEXT_FAINT), modifier = Modifier.size(30.dp))
                        }
                        IconButton(onClick = { act { it.zoneSettings(zoneId, loop = v.loopNext) } }, colors = IconButtonDefaults.iconButtonColors(containerColor = if (v.loopState > 0) soft else Color.Transparent, contentColor = if (v.loopState > 0) lit else dim),
                            modifier = Modifier.parity("now-playing.repeat", Contract.label("now-playing.repeat", v.loopState)).size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.np_repeat), contentDescription = null, modifier = Modifier.size(20.dp))
                                // The "1" inside the arrows, where the icon is hollow, as the page has it.
                                if (v.loop == "loop_one") Text("1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = lit)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayOnMenu(room.id, client, ::act, onRoom)   // the room followed: away, this phone's (item 487)
                        Spacer(Modifier.weight(1f))
                        VolumeMenu(v.volume, v.muted, volumeOpen, { volumeOpen = it })
                    }
                }
            }
            TouchNav("", onTap = onNav)
        }
    }
}

/** One of the two tabs, as the page's: small capitals tracked out, the brass line under the one on, as wide as its word. */
@Composable
private fun NpTab(text: String, on: Boolean, modifier: Modifier) {
    // As wide as the text, so the line under it is the word's width (filling the row's width pushed the Queue tab off the screen).
    // The page's tab: 12 over the word (2 below the discs' top, so 14 here), the word's own 18, 12 under it, the line of 2: 44.
    Column(modifier.padding(top = 14.dp).width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text.uppercase(), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600, letterSpacing = 0.14.em, maxLines = 1,
            color = if (on) paletteColor(Palette.MODAL_TEXT) else paletteColor(Palette.TEXT_FAINT))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background))
    }
}
