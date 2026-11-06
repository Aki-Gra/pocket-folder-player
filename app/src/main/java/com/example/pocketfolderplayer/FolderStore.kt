package com.example.pocketfolderplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Music is never copied, imported or modified. The URI grant is read-only. */
data class SavedFolder(val uri: String, val name: String)
data class AudioTrack(val uri: Uri, val filename: String)

object FolderStore {
    private const val NAME = "pocket_folder_player"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_FOLDER = "selected_folder"
    private const val KEY_TRACK = "selected_track"
    private const val KEY_POSITION = "selected_position_ms"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun all(context: Context): List<SavedFolder> {
        return runCatching {
            val array = JSONArray(prefs(context).getString(KEY_FOLDERS, "[]"))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                SavedFolder(item.getString("uri"), item.getString("name"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, folder: SavedFolder) {
        val folders = all(context).filterNot { it.uri == folder.uri } + folder
        writeFolders(context, folders)
    }

    fun remove(context: Context, uri: String) {
        writeFolders(context, all(context).filterNot { it.uri == uri })
        if (selected(context) == uri) {
            prefs(context).edit().remove(KEY_FOLDER).remove(KEY_TRACK).remove(KEY_POSITION).apply()
        }
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun writeFolders(context: Context, folders: List<SavedFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(JSONObject().put("uri", folder.uri).put("name", folder.name))
        }
        prefs(context).edit().putString(KEY_FOLDERS, array.toString()).apply()
    }

    fun selected(context: Context) = prefs(context).getString(KEY_FOLDER, null)
    fun select(context: Context, uri: String) {
        if (selected(context) != uri) {
            prefs(context).edit().putString(KEY_FOLDER, uri).remove(KEY_TRACK).remove(KEY_POSITION).apply()
        }
    }
    fun savedTrack(context: Context) = prefs(context).getString(KEY_TRACK, null)
    fun savedPosition(context: Context) = prefs(context).getLong(KEY_POSITION, 0L)
    fun savePlayback(context: Context, trackUri: String, positionMs: Long) {
        prefs(context).edit()
            .putString(KEY_TRACK, trackUri)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }
    fun shuffleEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SHUFFLE, false)
    fun repeatMode(context: Context): Int =
        prefs(context).getInt(KEY_REPEAT, androidx.media3.common.Player.REPEAT_MODE_ALL)
    fun saveShuffle(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, enabled).apply()
    }
    fun saveRepeat(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_REPEAT, mode).apply()
    }

    fun clearPlayback(context: Context) {
        prefs(context).edit().remove(KEY_TRACK).remove(KEY_POSITION).apply()
    }
}

object FolderScanner {
    suspend fun scan(context: Context, folder: SavedFolder): List<AudioTrack> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.uri))
            ?: return@withContext emptyList()
        root.listFiles()
            .asSequence()
            .filter { it.isFile && it.canRead() }
            .mapNotNull { item ->
                val name = item.name ?: return@mapNotNull null
                if (name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".mp4", ignoreCase = true)) {
                    AudioTrack(item.uri, name)
                } else null
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename })
            .toList()
    }
}
