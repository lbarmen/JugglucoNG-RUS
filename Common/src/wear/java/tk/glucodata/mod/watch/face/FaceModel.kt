package tk.glucodata.mod.watch.face

import java.time.ZoneId
import tk.glucodata.TrendProjection

/**
 * JugglucoNGmod, watch block (part 3a): what the face draws, made from a [FaceSnapshot] by pure rules - the renderer
 * (part 3b) only places it with [FaceLayout] and paints it.
 *
 * [hasValue] true - [ageText] is the age of that value (never of another, newer point); false - [valueText] is
 * [FaceValues.NONE], no arrow ([rate] NaN), no change, no colour; [ageText] still says how old the last reading is
 * (`null` when there has been none in the loaded history).
 */
data class FaceModel(
    val clock: FaceText.Clock,
    /** The weekday and the day of the month, "Wed 18" / "Ср 18" (part 7a: beside the clock, small). */
    val dateText: String,
    val hasValue: Boolean,
    val valueText: String,
    /** The value's colour as the app paints it ([FaceReading.color]); `null` without a value (the neutral tone). */
    val valueColor: Int?,
    /** mg/dL per minute for upstream's arrow; NaN - no arrow. */
    val rate: Float,
    val deltaText: String?,
    val ageMinutes: Int?,
    val ageText: String?,
    /** The age in the alarm colour (red): more than [FaceValues.AGE_ALARM_MINUTES]. */
    val ageAlarm: Boolean,
    val graph: FaceGraph.Plot,
    /** Battery percents (0..100), `null` - unknown or too old: no icon (part 7a). */
    val phoneBattery: Int?,
    val watchBattery: Int?,
    /** The full hours of local time in the graph's window, for its thin hour lines (part 3c2). */
    val hourMarks: List<FaceGraph.HourMark> = emptyList(),
    /**
     * The arrow's and the change's colour ([FaceReading.arrowColor]) while the reading is young enough for upstream's
     * 30-minute projection (`TrendProjection.MAX_DATA_AGE_MILLIS`), else [valueColor]; `null` without a value.
     */
    val arrowColor: Int? = valueColor,
) {
    /** The graph has a forecast to draw. */
    val hasForecast: Boolean get() = graph.forecast.isNotEmpty()

    companion object {
        fun build(snapshot: FaceSnapshot, zone: ZoneId, labels: FaceLabels): FaceModel {
            val now = snapshot.nowMs
            // The value itself must be young enough, not only the newest point of history.
            val shown = snapshot.current?.takeIf {
                FaceValues.usable(it.value) && it.text.isNotBlank() &&
                    !FaceValues.isNoValue(FaceValues.ageMinutes(now, it.timeMs))
            }
            val hasValue = shown != null
            // The age belongs to the number on the screen; only without one it is the newest reading known.
            val lastTime = shown?.timeMs ?: FaceValues.lastReadingTime(snapshot.current, snapshot.history)
            val age = lastTime?.let { FaceValues.ageMinutes(now, it) }
            val previous = shown?.let { FaceValues.previousFor(snapshot.history, it.timeMs) }
            // a forecast only from a value on the screen that is not yet in the alarm colour: from older readings it
            // would claim where glucose goes from a past that no longer describes now
            val forecast = if (hasValue && age != null && !FaceValues.isAgeAlarm(age)) snapshot.forecast else emptyList()
            val graph = FaceGraph.plot(snapshot.graphPoints, now, snapshot.graphHours, snapshot.low, snapshot.high,
                snapshot.isMmol, forecast)
            val arrowColor = shown?.let {
                if (now - it.timeMs <= TrendProjection.MAX_DATA_AGE_MILLIS) it.arrowColor else it.color
            }
            return FaceModel(
                clock = FaceText.clock(now, zone, snapshot.hour24),
                dateText = FaceText.weekdayAndDay(now, zone, labels),
                hasValue = hasValue,
                valueText = shown?.text ?: FaceValues.NONE,
                valueColor = shown?.color,
                rate = shown?.rate?.takeIf { it.isFinite() } ?: Float.NaN,
                deltaText = FaceValues.deltaText(shown, previous, snapshot.isMmol),
                ageMinutes = age,
                ageText = FaceText.age(age, labels),
                ageAlarm = age != null && FaceValues.isAgeAlarm(age),
                graph = graph,
                phoneBattery = snapshot.phoneBatteryPercent?.takeIf { it in 0..100 },
                watchBattery = snapshot.watchBatteryPercent?.takeIf { it in 0..100 },
                hourMarks = FaceGraph.hourMarks(graph.fromMs, graph.toMs, zone, snapshot.hour24),
                arrowColor = arrowColor,
            )
        }
    }
}
