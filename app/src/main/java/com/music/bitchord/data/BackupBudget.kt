package com.music.bitchord.data

import android.content.Context
import java.io.File

/**
 * Roughly what Android's Auto Backup takes of this app: everything under its
 * data folder except what the backup rules leave out. Android stops at 25 MB
 * and then backs up nothing at all for the app, so Settings shows how close
 * it is. Mirrors res/xml/data_extraction_rules.xml; a test keeps the two equal.
 */
object BackupBudget {
    const val LIMIT_BYTES = 25L * 1024L * 1024L

    /** (domain, path) pairs, as the backup rules write them. */
    val EXCLUDED: List<Pair<String, String>> = listOf(
        "sharedpref" to "bitchord_auth.xml",
        "sharedpref" to "bitchord_auth_plain.xml",
        "sharedpref" to "bitchord_secrets.xml",
        "sharedpref" to "bitchord_secrets_plain.xml",
        "sharedpref" to "bitchord_sources.xml",
        "sharedpref" to "bitchord_sources_plain.xml",
        "sharedpref" to "bitchord_tester.xml",
        "sharedpref" to "bitchord_last_played.xml",
        "sharedpref" to "bitchord_bounded_context.xml",
        "sharedpref" to "bitchord_party_queue_stash.xml",
        "file" to "beat_this_final0_q8.onnx",
        "file" to "vocals_umxhq_int8.onnx",
        "file" to "beat_this_int8.onnx",
        "file" to "downloads",
        "file" to "offline-hls",
        "file" to "download-artwork",
        "file" to "waveforms",
        "file" to "waveforms_played",
        "file" to "smart_analysis",
        "file" to "innertubex_players",
        "file" to "translate_ahead/download_waiting.json",
        "database" to "exoplayer_internal.db",
        "database" to "exoplayer_internal.db-journal",
        "database" to "exoplayer_internal.db-wal",
        "database" to "exoplayer_internal.db-shm",
        "root" to "app_webview",
        "root" to "app_textures",
        "sharedpref" to "innertubex_player_config.xml",
        "sharedpref" to "audio_cache_state.xml",
        "sharedpref" to "bitchord_addon_auth.xml",
        // Upstream 1.10's Drive backup: the account grant is this phone's, and the
        // other devices' listening is fetched from Drive again.
        "sharedpref" to "bitchord_drive_backup.xml",
        "file" to "listening-peers/",
        "file" to "listening-synced/",
    )

    /** Folders Auto Backup never takes, whatever the rules say. */
    private val NEVER = setOf("cache", "code_cache", "no_backup", "lib")

    /** Bytes the backup would take, counted the way its archive stores files: in 512-byte blocks, each with a header. */
    fun estimate(context: Context): Long = runCatching {
        estimate(File(context.applicationInfo.dataDir))
    }.getOrDefault(0L)

    internal fun estimate(dataDir: File): Long {
        var total = 0L
        dataDir.listFiles()?.forEach { top ->
            if (top.name in NEVER) return@forEach
            val domain = when (top.name) {
                "files" -> "file"
                "databases" -> "database"
                "shared_prefs" -> "sharedpref"
                else -> "root"
            }
            val base = if (domain == "root") dataDir else top
            top.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(base).invariantSeparatorsPath
                if (!excluded(domain, path)) total += 512L + (file.length() + 511L) / 512L * 512L
            }
        }
        return total
    }

    internal fun excluded(domain: String, path: String): Boolean =
        EXCLUDED.any { (d, p) -> d == domain && (path == p || path.startsWith("$p/")) }
}
