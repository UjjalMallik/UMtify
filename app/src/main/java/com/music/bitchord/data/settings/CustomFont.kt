package com.music.bitchord.data.settings

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The listener's own typeface, kept at one fixed path under the app's files.
 *
 * The picker's file name never becomes the stored name: the theme only ever has
 * to know one path, a re-import replaces the face in place, and clearing app data
 * takes it with everything else.
 */
object CustomFont {
    private const val DIR = "fonts"
    private const val FILE_NAME = "custom_font.ttf"
    private const val STAGED = "$FILE_NAME.tmp"

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    /** The stored face if it is still on disk, else null. */
    fun existing(context: Context): File? = file(context).takeIf { it.length() > 0L }

    /**
     * Copies [uri] over the stored face and returns the name to show for it, or
     * null when the pick could not be read or is not a font Android can parse.
     *
     * Staged beside the live file and only then renamed, so a bad pick never
     * costs the listener the font that was already working, and a face that
     * would fail at first layout never becomes the one the theme reads.
     */
    fun import(context: Context, uri: Uri): String? {
        val target = file(context)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, STAGED)
        val displayName = displayName(context, uri)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied || staged.length() == 0L) {
            staged.delete()
            return null
        }
        if (runCatching { Typeface.createFromFile(staged) }.getOrNull() == null) {
            staged.delete()
            return null
        }
        if (!staged.renameTo(target)) {
            target.delete()
            if (!staged.renameTo(target)) {
                staged.delete()
                return null
            }
        }
        return displayName
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Custom font"
    }
}
