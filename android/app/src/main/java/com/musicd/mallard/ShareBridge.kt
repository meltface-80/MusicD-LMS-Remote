package com.musicd.mallard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where a share card goes from the app's share sheet (ShareSheet): the clipboard (the card as a picture, through a
 * FileProvider address) or Pictures/Mallard. Ported from Mandarin.
 */
class ShareBridge(private val activity: Activity) {

    companion object {
        private const val TAG = "ShareBridge"

        /** Cleared on every share so the cache cannot grow without bound. */
        private const val SHARE_DIR = "shared"
    }

    /**
     * [bytes] saved to the phone's Pictures/Mallard as [fileName] (the native card's Download: item 75), where the
     * gallery shows it. Android 10 on needs no permission for this; before that, the storage permission the app asks for.
     */
    fun save(bytes: ByteArray, fileName: String, mime: String): Boolean {
        val name = safeName(fileName)
        val type = mime.ifEmpty { "image/png" }
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Mallard")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val r = activity.contentResolver
                val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw java.io.IOException("no stream")
                    r.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { r.delete(uri, null, null); throw e }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Mallard").apply { mkdirs() }
                val f = File(dir, name).apply { writeBytes(bytes) }
                android.media.MediaScannerConnection.scanFile(activity, arrayOf(f.path), arrayOf(type), null)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            false
        }
    }

    /**
     * [bytes] copied as a picture (the native card's Copy image, item 224; as Rouen's CardShare, v1.9.72-t22): Android's
     * clipboard holds a content:// address to the PNG, which the app it is pasted into reads, a forum's post among them.
     */
    fun copyImage(bytes: ByteArray, fileName: String): Boolean = try {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.shares", write(bytes, fileName))
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(activity.contentResolver, "Album card", uri))
        true
    } catch (e: Exception) {
        Log.w(TAG, "copy image failed", e)
        false
    }

    /** A share cache holding exactly the file being shared right now. */
    private fun write(bytes: ByteArray, fileName: String): File {
        val dir = File(activity.cacheDir, SHARE_DIR)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return File(dir, safeName(fileName)).apply { writeBytes(bytes) }
    }

    /** The page names the file; it must not be able to choose the path. */
    private fun safeName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(96)
        return name.ifEmpty { "card.png" }
    }
}
