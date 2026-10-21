package com.hotlayout.text

import android.icu.text.DateTimePatternGenerator
import android.icu.text.SimpleDateFormat
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import com.hotlayout.JsonValue
import java.util.Date
import java.util.Locale

/** Dates of the contract: what counts as a date, and how it is written. */
internal object DateText {
    /**
     * `x-template.dateTimePattern`: ISO 8601 with time and zone. `1850`, `2026-10-14` and a numeric
     * timestamp are NOT dates, whatever a platform parser accepts.
     */
    const val PATTERN = "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|([+-])(\\d{2}):(\\d{2}))$"
    private val expression = Regex(PATTERN)

    /** The longest string the expression accepts is 35 characters; a longer one is not run. */
    private const val MAX_LENGTH = 40

    /**
     * The instant of an ISO 8601 string in epoch milliseconds, or `null` if it is not a date. The
     * calendar is checked by code over the groups: platforms disagree about February 30.
     */
    fun moment(value: JsonValue?): Double? {
        val text = value?.string ?: return null
        if (text.length > MAX_LENGTH || !text.all { it.code < 0x80 }) return null
        val match = expression.matchEntire(text) ?: return null
        val groups = match.groupValues
        fun number(index: Int) = groups[index].toIntOrNull() ?: 0
        val year = number(1)
        val month = number(2)
        val day = number(3)
        val hour = number(4)
        val minute = number(5)
        val second = number(6)
        val offsetHour = number(10)
        val offsetMinute = number(11)
        if (month !in 1..12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59 || offsetHour > 23 || offsetMinute > 59) return null
        // Milliseconds: the first three fraction digits, as JavaScript reads them.
        val millis = (groups[7] + "000").take(3).toInt()
        val sign = if (groups[9] == "-") -1 else 1
        val offset = sign * (offsetHour * 60 + offsetMinute)
        val days = daysFromCivil(year, month, day)
        val seconds = days.toDouble() * 86_400 + (hour * 3600 + minute * 60 + second) - offset * 60
        return seconds * 1000 + millis
    }

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days from 1970-01-01 in the proleptic Gregorian calendar. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** CLDR skeletons of the formats (`x-dateParts`; the date without the field is `yMMMd`). */
    fun skeleton(format: String, dateParts: String?): String? = when (format) {
        "date" -> when (dateParts) {
            "dayMonth" -> "MMMMd"
            "weekdayDayMonth" -> "MMMMEEEEd"
            "dayMonthYear" -> "yMMMMd"
            else -> "yMMMd"
        }
        "time" -> "jmm"
        else -> null
    }

    private fun write(moment: Double, skeleton: String, locale: String?, zone: java.util.TimeZone?): String {
        // `x-template.dateCalendar`: Gregorian in every locale (otherwise Thai would write 2569).
        val base = NumberText.uLocale(locale)
        val uLocale = base.setKeywordValue("calendar", "gregorian")
        val pattern = DateTimePatternGenerator.getInstance(uLocale).getBestPattern(skeleton)
        val icuZone = TimeZone.getTimeZone((zone ?: java.util.TimeZone.getDefault()).id)
        val format = SimpleDateFormat(pattern, uLocale)
        format.calendar = GregorianCalendar(icuZone, uLocale)
        format.timeZone = icuZone
        return format.format(Date(moment.toLong()))
    }

    /** A date, a time or both in a locale, or `null` when the format does not apply. */
    fun format(moment: Double, format: String, dateParts: String?, locale: String?, zone: java.util.TimeZone?): String? {
        if (format == "dateTime") {
            // Date and time are joined by the LANGUAGE's pattern (CLDR, the medium joiner).
            val date = write(moment, "yMMMd", locale, zone)
            val time = write(moment, "jmm", locale, zone)
            return glue(locale).replace("{1}", date).replace("{0}", time)
        }
        val skeleton = skeleton(format, dateParts) ?: return null
        return write(moment, skeleton, locale, zone)
    }

    /** The CLDR medium date-time joiner of a language (`{1}` is the date, `{0}` the time). */
    fun glue(locale: String?): String {
        val tag = locale?.let(LocaleTags::normalize) ?: Locale.getDefault().toLanguageTag()
        for (candidate in LocaleTags.lookupChain(tag)) dateTimeGlue[candidate]?.let { return it }
        return "{1}, {0}"
    }

    /** CLDR `dateTimeFormats/medium` (the non-«at» form) where it is not `{1}, {0}`. */
    private val dateTimeGlue: Map<String, String> = mapOf(
        "af" to "{1} {0}", "am" to "{1} {0}", "ar" to "{1}، {0}", "ar-DZ" to "{1}، {0}", "ar-EG" to "{1}، {0}",
        "as" to "{1} {0}", "az" to "{1} {0}", "bm" to "{1} {0}", "bn" to "{1} {0}", "bs" to "{1} {0}", "cs" to "{1} {0}",
        "cy" to "{1} {0}", "da" to "{1} {0}", "et" to "{1} {0}", "eu" to "{1} ({0})", "fa" to "{1}،‏ {0}",
        "fi" to "{1} {0}", "fur" to "{1} {0}", "ga" to "{1} {0}", "gsw" to "{1} {0}", "gu" to "{1} {0}", "haw" to "{1} {0}",
        "hr" to "{1} {0}", "hu" to "{1} {0}", "id" to "{1} {0}", "in" to "{1} {0}", "ja" to "{1} {0}", "kn" to "{1} {0}",
        "ko" to "{1} {0}", "ky" to "{1} {0}", "ln" to "{1} {0}", "lt" to "{1} {0}", "lv" to "{1} {0}", "mg" to "{1} {0}",
        "mn" to "{1} {0}", "mt" to "{1} {0}", "my" to "{1} {0}", "nyn" to "{1} {0}", "ps" to "{1} {0}", "pt" to "{1} {0}",
        "pt-BR" to "{1} {0}", "si" to "{1} {0}", "sr" to "{1} {0}", "sr-Latn" to "{1} {0}", "sv" to "{1} {0}", "sw" to "{1} {0}",
        "te" to "{1} {0}", "th" to "{1} {0}", "tr" to "{1} {0}", "ur" to "{1} {0}", "vi" to "{0} {1}", "zh" to "{1} {0}",
        "zh-CN" to "{1} {0}", "zh-HK" to "{1}{0}", "zh-TW" to "{1}{0}", "zu" to "{1} {0}",
    )
}
