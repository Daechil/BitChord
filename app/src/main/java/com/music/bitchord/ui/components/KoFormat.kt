package com.music.bitchord.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.screens.compactTokens
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * How the KO screens and notifications write amounts, token counts and
 * times, in one place so every one of them reads the same: dollars, won or
 * both as Settings has it; "1.2만" tokens in Korean; "오늘 14:20" rather
 * than "11-20 14:20".
 */
class KoFormat(
    private val context: Context,
    private val display: String = AppSettings.moneyDisplay.value,
    private val krwPerUsd: Int = AppSettings.krwPerUsd.value,
) {
    private val locale: Locale get() = Locale.getDefault()

    /** "$0.42", "$0.012" — three places under ten cents, where two would say nothing. */
    fun usd(value: Double): String =
        "$" + String.format(Locale.US, if (value > 0.0 && value < 0.1) "%.3f" else "%.2f", value)

    /** "약 590원" at the rate Settings has; "1원 미만" for a sliver. */
    fun krw(value: Double): String {
        val won = value * krwPerUsd
        if (won > 0.0 && won < 1.0) return context.getString(R.string.ko_money_krw_tiny)
        return context.getString(R.string.ko_money_krw, NumberFormat.getIntegerInstance(locale).format(won.roundToLong()))
    }

    /** An amount as Settings says to show amounts. */
    fun money(value: Double): String = when (display) {
        AppSettings.MONEY_USD -> usd(value)
        AppSettings.MONEY_KRW -> krw(value)
        else -> context.getString(R.string.ko_money_both, usd(value), krw(value))
    }

    /** A limit, which is a round figure: "$1", "$0.50", with won as Settings has it. */
    fun limit(value: Double): String {
        val dollars = if (value == Math.floor(value)) "$" + value.toLong() else "$" + String.format(Locale.US, "%.2f", value)
        return when (display) {
            AppSettings.MONEY_USD -> dollars
            AppSettings.MONEY_KRW -> krw(value)
            else -> context.getString(R.string.ko_money_both, dollars, krw(value))
        }
    }

    fun tokens(count: Long): String = compactTokens(count)

    /** "입력 1.2만 · 출력 340 토큰". */
    fun inOut(input: Long, output: Long): String =
        context.getString(R.string.ko_tokens_in_out, tokens(input), tokens(output))

    /** "오늘 14:20", "어제 09:10", "11월 15일 14:20", and the year as well when it is not this one. */
    fun time(at: Long, withTime: Boolean = true, seconds: Boolean = false): String {
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(at).atZone(zone)
        val day = moment.toLocalDate()
        val today = LocalDate.now(zone)
        val clock = moment.format(DateTimeFormatter.ofPattern(if (seconds) "HH:mm:ss" else "HH:mm", locale))
        val date = when (day) {
            today -> return if (withTime) context.getString(R.string.ko_time_today, clock) else context.getString(R.string.ko_today)
            today.minusDays(1) -> return if (withTime) context.getString(R.string.ko_time_yesterday, clock) else context.getString(R.string.ko_yesterday)
            else -> moment.format(
                DateTimeFormatter.ofPattern(
                    DateFormat.getBestDateTimePattern(locale, if (day.year == today.year) "MMMd" else "yMMMd"),
                    locale,
                ),
            )
        }
        return if (withTime) "$date $clock" else date
    }

    /** "2026-11" as "2026년 11월". */
    fun month(value: String): String = runCatching {
        YearMonth.parse(value).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale))
    }.getOrDefault(value)
}

/** [KoFormat] for a screen, written again when the amount display or the rate changes. */
@Composable
fun koFormat(): KoFormat {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val display by AppSettings.moneyDisplay.collectAsStateWithLifecycle()
    val rate by AppSettings.krwPerUsd.collectAsStateWithLifecycle()
    return remember(context, configuration, display, rate) { KoFormat(context, display, rate) }
}
