package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/**
 * BitChord ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    private const val CACHE_SUBDIR = "updates"

    // Upstream's releases, or — for this fork's numbered builds — the fork's
    // own, whose APKs install over this package. See app/build.gradle.kts.
    private val LATEST_RELEASE_URL =
        "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    // With "Get beta versions" on: the newest releases, pre-releases among them —
    // "latest" never points at a pre-release.
    private val RECENT_RELEASES_URL =
        "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=$RECENT_RELEASES"
    private const val RECENT_RELEASES = 15

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    /** What a check found: a newer build, none, or why it could not tell. */
    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class Found(val info: UpdateInfo) : CheckResult
        data class Failed(val reason: FailReason, val httpCode: Int? = null) : CheckResult
    }

    enum class FailReason { OFFLINE, RATE_LIMITED, NO_RELEASE, HTTP, UNREADABLE }

    /** Why a GitHub answer was not a release, from its status and rate-limit header. */
    internal fun failureFor(code: Int, rateRemaining: String?): FailReason = when {
        (code == 403 || code == 429) && (rateRemaining == "0" || code == 429) -> FailReason.RATE_LIMITED
        code == 404 -> FailReason.NO_RELEASE
        else -> FailReason.HTTP
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val beta = com.music.bitchord.data.settings.AppSettings.betaUpdates.value
        try {
            val request = Request.Builder().url(if (beta) RECENT_RELEASES_URL else LATEST_RELEASE_URL).build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Failed(
                        failureFor(response.code, response.header("x-ratelimit-remaining")),
                        response.code,
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
                    ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                val release = if (beta) {
                    val list = parsed as? kotlinx.serialization.json.JsonArray
                        ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                    newestOf(list) ?: return@withContext CheckResult.Failed(FailReason.NO_RELEASE)
                } else {
                    parsed as? JsonObject ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                }
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                val url = release["html_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext CheckResult.Failed(FailReason.UNREADABLE)
                val apkUrl = apkAssetUrl(release)
                val latest = tag.removePrefix("v")
                // KO: on the beta channel, every release since this build — not only the newest.
                val notes = (parsed as? kotlinx.serialization.json.JsonArray)
                    ?.let { notesBetween(it, BuildConfig.VERSION_NAME, latest, includeBeta = true) }
                    ?: release["body"]?.jsonPrimitive?.contentOrNull
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                    val info = UpdateInfo(latest, url, apkUrl, notes)
                    _available.value = info
                    CheckResult.Found(info)
                } else {
                    CheckResult.UpToDate
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            CheckResult.Failed(FailReason.OFFLINE)
        } catch (_: Exception) {
            CheckResult.Failed(FailReason.UNREADABLE)
        }
    }

    /**
     * KO: the notes of the release this build came from (its tag is "v" and
     * the version name), for the what's-new window after an update. Null when
     * there is none to read or GitHub cannot be reached; asked again next launch.
     */
    suspend fun notesFor(version: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/v$version"
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                (json.parseToJsonElement(body) as? JsonObject)?.get("body")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * KO: the notes of every release after [seen] up to [current], newest first,
     * for the what's-new window — a beta skipped, or several updates at once,
     * are not lost. Just [current]'s when [seen] is unknown; null when GitHub
     * cannot be reached, and asked again next launch.
     */
    suspend fun notesSince(seen: String?, current: String, includeBeta: Boolean): String? = withContext(Dispatchers.IO) {
        if (seen.isNullOrBlank()) return@withContext notesFor(current)
        runCatching {
            Http.client.newCall(Request.Builder().url(RECENT_RELEASES_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val list = json.parseToJsonElement(response.body?.string() ?: return@use null)
                    as? kotlinx.serialization.json.JsonArray ?: return@use null
                notesBetween(list, seen, current, includeBeta)
            }
        }.getOrNull() ?: notesFor(current)
    }

    /**
     * The bodies of [releases] newer than [after] and up to [upTo], newest first,
     * each under a "## v<version>" line (see ReleaseNotesPages); betas only when
     * [includeBeta], and never under a stable [upTo], whose notes sum them up.
     * At most [MAX_COMBINED] releases. Null when none qualifies.
     */
    internal fun notesBetween(
        releases: kotlinx.serialization.json.JsonArray,
        after: String,
        upTo: String,
        includeBeta: Boolean,
    ): String? {
        val picked = releases.mapNotNull { it as? JsonObject }
            .filter { it["draft"]?.jsonPrimitive?.contentOrNull != "true" }
            .mapNotNull { release ->
                val version = release["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return@mapNotNull null
                val body = release["body"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                (version to body).takeIf {
                    isNewer(version, after) && !isNewer(version, upTo) &&
                        (includeBeta || !version.endsWith("-beta") || version == upTo) &&
                        // A stable release's notes already sum up the betas before it.
                        (upTo.endsWith("-beta") || !version.endsWith("-beta"))
                }
            }
            .sortedWith { a, b -> if (isNewer(a.first, b.first)) -1 else if (isNewer(b.first, a.first)) 1 else 0 }
            .take(MAX_COMBINED)
        if (picked.isEmpty()) return null
        if (picked.size == 1) return picked.single().second
        // Each body's own footer goes; ReleaseNotesPages would only find the last one.
        return picked.joinToString("\n\n") { (version, body) -> "## v$version\n\n${ReleaseNotesPages.withoutFooter(body).trim()}" }
    }

    private const val MAX_COMBINED = 6

    /**
     * The highest version among [releases], stable or beta: drafts and
     * releases without a tag left out. Null when none is left.
     */
    internal fun newestOf(releases: kotlinx.serialization.json.JsonArray): JsonObject? =
        releases.mapNotNull { it as? JsonObject }
            .filter { it["draft"]?.jsonPrimitive?.contentOrNull != "true" }
            .mapNotNull { release ->
                release["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v")?.let { tag -> tag to release }
            }
            .fold(null as Pair<String, JsonObject>?) { best, candidate ->
                if (best == null || isNewer(candidate.first, best.first)) candidate else best
            }
            ?.second

    /** For tests: [isNewer] on two version names. */
    internal fun newer(latest: String, current: String): Boolean = isNewer(latest, current)

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * One release carries the phone app and the TV app, each as a universal
     * APK plus per-ABI splits:
     *
     *     BitChord-v1.8.apk     BitChord-v1.8_arm64-v8a.apk     ...
     *     BitChordTV-v1.8.apk   BitChordTV-v1.8_arm64-v8a.apk   ...
     *
     * so this app's APKs are the ones named with its own
     * [BuildConfig.UPDATE_APK_PREFIX] — "BitChord-" never matches a TV file,
     * since the prefix ends at the hyphen. The universal build (no `_ABI`
     * suffix) is taken first, as it installs anywhere; failing that, the split
     * for this device's ABI. A release with neither leaves
     * [UpdateInfo.apkUrl] null and the UI falls back to opening the releases
     * page.
     */
    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val apks = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ours = name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
                if (ours) name.removeSuffix(".apk").removeSuffix(".APK") to url else null
            }
            .orEmpty()
        apks.firstOrNull { (name, _) -> '_' !in name }?.second
            ?: Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second
            }
    }.getOrNull()

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bitchord-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     * Some TV builds have no such settings screen; there the installer is
     * launched anyway and shows its own "not allowed" prompt with a way in.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    /**
     * A version split into its numeric dotted parts, this fork's build number
     * ("-ko.7" — a release of its own, not a pre-release), and whether it
     * carries any other "-suffix" (e.g. "-beta2").
     */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean, val koBuild: Int? = null)

    private val KO_BUILD = Regex("""-ko\.(\d+)""")

    private fun parseVersion(raw: String): ParsedVersion {
        val koBuild = KO_BUILD.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val rest = KO_BUILD.replace(raw, "")
        val dash = rest.indexOf('-')
        val base = if (dash >= 0) rest.substring(0, dash) else rest
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0, koBuild)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build (see the debug build type's
     * `versionNameSuffix` in app/build.gradle.kts) is treated as older than a
     * plain release of the same numbers, since the beta by definition predates
     * the tag it was testing toward. Without this, a beta and the release it
     * matches compare equal and testers never get nudged onto the real build.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Same upstream version: the later fork build wins.
        val lb = l.koBuild ?: 0
        val cb = c.koBuild ?: 0
        if (lb != cb) return lb > cb
        return c.isPreRelease && !l.isPreRelease
    }
}
