package tk.glucodata.mod.watch.face

import java.time.ZoneId

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
    val dateText: String,
    val hasValue: Boolean,
    val valueText: String,
    /** Upstream's range colour of the value; `null` without a value (the renderer's neutral tone). */
    val valueColor: Int?,
    /** mg/dL per minute for upstream's arrow; NaN - no arrow. */
    val rate: Float,
    val deltaText: String?,
    val ageMinutes: Int?,
    val ageText: String?,
    /** The age in the alarm colour (red): more than [FaceValues.AGE_ALARM_MINUTES]. */
    val ageAlarm: Boolean,
    val graph: FaceGraph.Plot,
    val phoneBatteryText: String,
    val watchBatteryText: String,
) {
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
            return FaceModel(
                clock = FaceText.clock(now, zone, snapshot.hour24),
                dateText = FaceText.date(now, zone, labels),
                hasValue = hasValue,
                valueText = shown?.text ?: FaceValues.NONE,
                valueColor = shown?.color,
                rate = shown?.rate?.takeIf { it.isFinite() } ?: Float.NaN,
                deltaText = FaceValues.deltaText(shown, previous, snapshot.isMmol),
                ageMinutes = age,
                ageText = FaceText.age(age, labels),
                ageAlarm = age != null && FaceValues.isAgeAlarm(age),
                graph = FaceGraph.plot(snapshot.graphPoints, now, snapshot.graphHours, snapshot.low, snapshot.high,
                    snapshot.isMmol),
                phoneBatteryText = FaceText.battery(snapshot.phoneBatteryPercent, labels),
                watchBatteryText = FaceText.battery(snapshot.watchBatteryPercent, labels),
            )
        }
    }
}
