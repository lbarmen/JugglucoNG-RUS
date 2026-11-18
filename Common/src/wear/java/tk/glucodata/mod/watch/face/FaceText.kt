package tk.glucodata.mod.watch.face

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import tk.glucodata.R

/**
 * JugglucoNGmod, watch block (part 3a): the face's words, from our `mod_watch_` resources (English and Russian), so
 * the date reads "Tue 17 Nov" / "Вт 17 ноя" rather than the platform's long or dotted forms ("нояб.").
 */
data class FaceLabels(
    /** Seven short weekday names from Monday. */
    val days: List<String>,
    /** Twelve short month names from January. */
    val months: List<String>,
    /** A battery percent, `String.format` pattern with one integer ("%1$d%%"). */
    val percentFormat: String,
    /** The age of the value, `String.format` pattern with one integer ("%1$d min"). */
    val minutesFormat: String,
    /** The battery row's words before the two percents (part 3b). */
    val phone: String = "phone",
    val watch: String = "watch",
) {
    companion object {
        /** The labels for the context's current locale. */
        fun from(context: Context): FaceLabels {
            val res = context.resources
            return FaceLabels(
                res.getStringArray(R.array.mod_watch_face_days).toList(),
                res.getStringArray(R.array.mod_watch_face_months).toList(),
                res.getString(R.string.mod_watch_face_percent),
                res.getString(R.string.mod_watch_face_minutes),
                res.getString(R.string.mod_watch_face_phone),
                res.getString(R.string.mod_watch_face_watch),
            )
        }
    }
}

/** Texts of the face: pure functions over a time, a zone and [FaceLabels]. */
object FaceText {

    /** The clock: "14:35" in 24-hour mode, else "2:35" with [dayPart] "pm" (upstream's face draws it so). */
    data class Clock(val text: String, val dayPart: String?)

    fun clock(epochMs: Long, zone: ZoneId, hour24: Boolean): Clock {
        val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        if (hour24) return Clock(String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute), null)
        val hour12 = (time.hour % 12).let { if (it == 0) 12 else it }
        return Clock(String.format(Locale.ROOT, "%d:%02d", hour12, time.minute), if (time.hour >= 12) "pm" else "am")
    }

    /** "Tue 17 Nov": the weekday, the day of the month and the month, from [labels]. */
    fun date(epochMs: Long, zone: ZoneId, labels: FaceLabels): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val day = labels.days.getOrNull(date.dayOfWeek.value - 1).orEmpty()
        val month = labels.months.getOrNull(date.monthValue - 1).orEmpty()
        return listOf(day, date.dayOfMonth.toString(), month).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * "18 Nov" / "18 ноя": the day of the month and the short month, from [labels] (part 7e, the face's bottom row;
     * the weekday is left out). The painter splits it at the space.
     */
    fun dayAndMonth(epochMs: Long, zone: ZoneId, labels: FaceLabels): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val month = labels.months.getOrNull(date.monthValue - 1).orEmpty()
        return listOf(date.dayOfMonth.toString(), month).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A battery as "64%" / "64 %"; [FaceValues.NONE] when unknown or too old. */
    fun battery(percent: Int?, labels: FaceLabels): String {
        if (percent == null || percent !in 0..100) return FaceValues.NONE
        return formatInt(labels.percentFormat, percent, "$percent%")
    }

    /** The battery row, "phone 64%   watch 81%" / "телефон 64 %   часы 81 %" (texts from [battery]). */
    fun batteries(phoneText: String, watchText: String, labels: FaceLabels): String =
        "${labels.phone} $phoneText   ${labels.watch} $watchText"

    /** The age of the value, "2 min" / "2 мин"; `null` when there has been no reading at all. */
    fun age(minutes: Int?, labels: FaceLabels): String? {
        if (minutes == null || minutes < 0) return null
        return formatInt(labels.minutesFormat, minutes, "$minutes min")
    }

    private fun formatInt(pattern: String, value: Int, fallback: String): String = try {
        String.format(Locale.ROOT, pattern, value)
    } catch (_: Throwable) {
        fallback
    }
}
