package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.util.UUID

/**
 * Tidies up after Android restored this app's data from a backup — a new
 * phone, or the app installed again. The settings come back but the music
 * files do not (they are too large to back up), so the list of downloads would
 * point at files that are not there, or, for songs saved to the phone's Music
 * folder, at whatever another phone's numbering now names. That list, the
 * chosen music folder (whose permission is not restored either) and the
 * queue of downloads waiting for a translation are cleared; translations,
 * settings and listening history stay.
 *
 * A restore is told apart by a token kept twice: once in the settings, which
 * are backed up, and once outside what is backed up. After a restore only
 * the first comes back, and the two differ.
 */
object RestoreGuard {
    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_settings"
    const val KEY_INSTALL_TOKEN = "ko_install_token"
    private const val TOKEN_FILE = "install_token"

    /** Settings keys that describe files on this phone, cleared after a restore. */
    internal val DEVICE_FILE_KEYS = listOf(
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
        "local_music_folder_uri",
    )

    /** What [check] decided, for its tests. */
    internal enum class Outcome { FIRST_RUN, SAME_INSTALL, RESTORED }

    internal fun outcome(settingsToken: String?, localToken: String?): Outcome = when {
        settingsToken.isNullOrBlank() || localToken.isNullOrBlank() ->
            if (!settingsToken.isNullOrBlank() && localToken.isNullOrBlank()) Outcome.RESTORED else Outcome.FIRST_RUN
        settingsToken == localToken -> Outcome.SAME_INSTALL
        else -> Outcome.RESTORED
    }

    /** Before the settings and the downloads list are read. */
    fun check(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val tokenFile = File(context.noBackupFilesDir, TOKEN_FILE)
            val local = tokenFile.takeIf { it.isFile }?.readText()?.trim()
            val stored = prefs.getString(KEY_INSTALL_TOKEN, null)
            when (outcome(stored, local)) {
                Outcome.SAME_INSTALL -> return
                Outcome.RESTORED -> {
                    Log.i(TAG, "restore guard: data restored from a backup; clearing this phone's file lists")
                    val edit = prefs.edit()
                    DEVICE_FILE_KEYS.forEach { edit.remove(it) }
                    edit.commit()
                    File(context.filesDir, "translate_ahead/download_waiting.json").delete()
                }
                Outcome.FIRST_RUN -> Unit
            }
            val token = UUID.randomUUID().toString()
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
            prefs.edit().putString(KEY_INSTALL_TOKEN, token).commit()
        }.onFailure { Log.w(TAG, "restore guard: ${it.javaClass.simpleName}") }
    }
}
