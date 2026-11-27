package com.musicd.mallard

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.musicd.mallard.client.Zone

/**
 * Now playing on the home screen: cover, title, artist, and transport.
 *
 * The cover is also the random-album button — the app's most-used action on
 * the widget's biggest target. Drawn by NowPlayingService whenever the room
 * changes; the platform's own timer is off (updatePeriodMillis = 0).
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, NowPlayingService.latest, NowPlayingService.latestArt, NowPlayingService.reachable)
        // Placing the widget is a reason to start following the room — where Android
        // allows it from here (from Android 12 a widget update can't start a
        // foreground service; the app starts it when it next opens).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) NowPlayingService.start(context)
    }

    companion object {
        fun render(context: Context, zone: Zone?, art: Bitmap?, reachable: Boolean) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids == null || ids.isEmpty()) return
            val v = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val np = zone?.nowPlaying
            v.setTextViewText(R.id.widget_title, when {
                Store.server(context) == null -> "Open Mallard to connect"
                !reachable -> "Can't reach MusicD LMS Remote"
                np != null -> np.title
                else -> "Nothing playing"
            })
            v.setTextViewText(R.id.widget_subtitle, when {
                np != null -> listOf(np.artist, zone?.name ?: "").filter { it.isNotBlank() }.joinToString(" · ")
                else -> "Tap the cover for a random album"
            })
            if (art != null && np != null) v.setImageViewBitmap(R.id.widget_art, art)
            else v.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher_foreground)
            v.setImageViewResource(
                R.id.widget_playpause,
                if (zone?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            v.setOnClickPendingIntent(R.id.widget_previous, NowPlayingService.pending(context, NowPlayingService.ACTION_PREVIOUS))
            v.setOnClickPendingIntent(R.id.widget_playpause, NowPlayingService.pending(context, NowPlayingService.ACTION_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.widget_next, NowPlayingService.pending(context, NowPlayingService.ACTION_NEXT))
            v.setOnClickPendingIntent(R.id.widget_art, NowPlayingService.pending(context, NowPlayingService.ACTION_RANDOM))
            try {
                manager.updateAppWidget(ids, v)
            } catch (e: Exception) {
                android.util.Log.w("NowPlayingWidget", "could not draw the widget", e)
            }
        }
    }
}
