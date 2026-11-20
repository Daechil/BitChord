package com.music.bitchord.download

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The dollar's rate in won, fetched at most once a day while Settings says
 * "automatic": from the European Central Bank's reference rates (through
 * Frankfurter, no key), or from the Korea Eximbank's when its key is set. The
 * last rate fetched is kept and used until a newer one comes; nothing is ever
 * sent but the request itself.
 *
 * The services' addresses have moved before, so each is tried at its current
 * address and then its older one, and the ECB rate falls back on another free
 * service; a failure says which was tried and why, for Settings to show.
 */
object ExchangeRates {
    private const val TAG = "BitChord"
    private const val TIMEOUT_MS = 10_000
    /** The Eximbank publishes on business days, late in the morning: a few days back finds the last one. */
    private const val EXIM_DAYS_BACK = 7

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetching = Mutex()
    private var started = false

    /** Why the last fetch failed, for Settings; null after one that worked. */
    @Volatile
    var lastError: String? = null
        private set

    /** When the last automatic fetch was tried; "fetch now" is not held back by it. */
    @Volatile
    private var lastAutoTry = 0L
    private const val RETRY_GAP_MS = 30L * 60L * 1000L

    fun init(context: Context) {
        if (started) return
        started = true
        scope.launch { refreshIfDue() }
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { refreshIfDue() }
                }
            })
        }
    }

    private fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()

    /** Fetches when automatic and nothing has been fetched today. */
    private suspend fun refreshIfDue() {
        if (AppSettings.krwRateMode.value != AppSettings.RATE_AUTO) return
        val last = AppSettings.fetchedRate.value
        val fetchedDay = if (last.at > 0) {
            java.time.Instant.ofEpochMilli(last.at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } else {
            ""
        }
        if (fetchedDay == today() && last.krw > 0) return
        // A failed day is not asked again every time the app comes to the front.
        val now = System.currentTimeMillis()
        if (now - lastAutoTry < RETRY_GAP_MS) return
        lastAutoTry = now
        refresh()
    }

    /** Fetches now, from the source Settings chose; true when a rate came. */
    suspend fun refresh(): Boolean = fetching.withLock {
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            val key = AppSettings.eximApiKey.value
            val rate = (if (AppSettings.krwRateSource.value == AppSettings.RATE_SOURCE_EXIM && key.isNotBlank()) {
                fromExim(key, errors)
            } else {
                null
            }) ?: fromEcb(errors) ?: fromOpenEr(errors)
            if (rate != null) {
                AppSettings.setFetchedRate(rate)
                lastError = null
                true
            } else {
                lastError = errors.joinToString(" · ").ifBlank { "no answer" }
                Log.d(TAG, "exchange rate: $lastError")
                false
            }
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> attempt(name: String, errors: MutableList<String>, block: () -> T?): T? = try {
        block().also { if (it == null) errors += "$name: no rate" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        errors += "$name: ${error.message ?: error.javaClass.simpleName}"
        null
    }

    private fun sane(value: Double?): Double? =
        value?.takeIf { it >= AppSettings.MIN_KRW_PER_USD && it <= AppSettings.MAX_KRW_PER_USD }

    private fun JsonElement.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)
    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.content

    /** The ECB's reference rate, through Frankfurter: `{"base":"USD","date":"…","rates":{"KRW":…}}`. */
    private fun fromEcb(errors: MutableList<String>): AppSettings.FetchedRate? {
        val urls = listOf(
            "https://api.frankfurter.dev/v1/latest?base=USD&symbols=KRW",
            "https://api.frankfurter.app/latest?from=USD&to=KRW",
        )
        for (url in urls) {
            attempt("ECB", errors) {
                val body = json.parseToJsonElement(get(url))
                val krw = sane(body.field("rates")?.field("KRW")?.text()?.toDoubleOrNull()) ?: return@attempt null
                AppSettings.FetchedRate(krw, body.field("date")?.text().orEmpty(), AppSettings.RATE_SOURCE_ECB, System.currentTimeMillis())
            }?.let { return it }
        }
        return null
    }

    /** ExchangeRate-API's free rate: `{"result":"success","time_last_update_unix":…,"rates":{"KRW":…}}`. */
    private fun fromOpenEr(errors: MutableList<String>): AppSettings.FetchedRate? = attempt("open.er-api", errors) {
        val body = json.parseToJsonElement(get("https://open.er-api.com/v6/latest/USD"))
        if (body.field("result")?.text() != "success") return@attempt null
        val krw = sane(body.field("rates")?.field("KRW")?.text()?.toDoubleOrNull()) ?: return@attempt null
        val date = body.field("time_last_update_unix")?.text()?.toLongOrNull()?.let {
            java.time.Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        }.orEmpty()
        AppSettings.FetchedRate(krw, date, AppSettings.RATE_SOURCE_OPEN_ER, System.currentTimeMillis())
    }

    /**
     * The Eximbank's base rate for the day (매매기준율), `deal_bas_r` of the
     * USD row: an empty list on weekends, holidays and before it is published,
     * so the days before are asked in turn. A `result` other than 1 is the key
     * being refused (3) or the day's calls used up (4).
     */
    private fun fromExim(key: String, errors: MutableList<String>): AppSettings.FetchedRate? {
        val hosts = listOf("https://oapi.koreaexim.go.kr", "https://www.koreaexim.go.kr")
        val format = DateTimeFormatter.BASIC_ISO_DATE
        val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
        for (host in hosts) {
            var reached = false
            for (back in 0 until EXIM_DAYS_BACK) {
                val day = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(back.toLong())
                val url = "$host/site/program/financial/exchangeJSON?authkey=$encodedKey&searchdate=${day.format(format)}&data=AP01"
                val found = attempt("Eximbank", errors) {
                    val rows = json.parseToJsonElement(get(url)) as? JsonArray ?: return@attempt null
                    reached = true
                    val refused = rows.firstNotNullOfOrNull { it.field("result")?.text()?.toIntOrNull()?.takeIf { code -> code != 1 } }
                    if (refused != null) throw java.io.IOException(if (refused == 3) "key refused" else "result $refused")
                    val usd = rows.firstOrNull { it.field("cur_unit")?.text()?.trim() == "USD" } ?: return@attempt EMPTY_DAY
                    val krw = sane(usd.field("deal_bas_r")?.text()?.replace(",", "")?.toDoubleOrNull()) ?: return@attempt null
                    AppSettings.FetchedRate(krw, day.toString(), AppSettings.RATE_SOURCE_EXIM, System.currentTimeMillis())
                }
                if (found === EMPTY_DAY) continue
                if (found != null) return found
                // An error on this host: the next host, not the day before.
                break
            }
            if (reached) {
                errors += "Eximbank: no rate in the last $EXIM_DAYS_BACK days"
                break
            }
        }
        return null
    }

    /** A day the Eximbank published nothing for: look at the one before. */
    private val EMPTY_DAY = AppSettings.FetchedRate(krw = -1.0)
}
