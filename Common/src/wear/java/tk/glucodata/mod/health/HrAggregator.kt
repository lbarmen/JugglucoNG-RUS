package tk.glucodata.mod.health

import java.util.TreeMap
import kotlin.math.roundToInt
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 4): heart rate as 5-minute means on the watch (as Glucove did, decision of its
 * user 21.10.2026): the sensor gives a reading about every second, the phone gets one mean per window with the count,
 * minimum and maximum. Windows are aligned to the clock; readings without skin contact and zeros are skipped. Open
 * windows are kept as a line ([encode] / [decode]) so they survive a restart of the watch process.
 *
 * A reading that comes after its window was closed (a late delivery) opens the window again: it goes as a second
 * [ModHealthBatch.KIND_HR5] row with the same start, which the phone merges by the counts (part 5, rule in
 * [ModHealthBatch.Sample]). [add] tells such readings apart so the watch can count them ([closedUntil]).
 */
class HrAggregator(private val windowMs: Long = HealthConfig.HR_WINDOW_MS) {

    private data class Bucket(val start: Long, val sum: Double, val count: Int, val min: Float, val max: Float)

    private val open = TreeMap<Long, Bucket>()

    val openCount: Int get() = open.size

    /** The end of the latest window closed so far (0 - none): a reading before it is late. */
    var closedUntil: Long = 0L
        private set

    /**
     * Adds one reading; [noContact] readings, zeros, negatives and NaN are skipped. Returns `true` when the reading was
     * taken although its window was already closed (late).
     */
    fun add(timeMs: Long, bpm: Float, noContact: Boolean = false): Boolean {
        if (noContact || !(bpm > 0f) || bpm.isInfinite()) return false
        val start = timeMs - Math.floorMod(timeMs, windowMs)
        val b = open[start]
        open[start] = if (b == null) {
            Bucket(start, bpm.toDouble(), 1, bpm, bpm)
        } else {
            b.copy(sum = b.sum + bpm, count = b.count + 1, min = minOf(b.min, bpm), max = maxOf(b.max, bpm))
        }
        return start < closedUntil
    }

    /**
     * Closes the windows that ended at or before [nowMs] - [graceMs] (no more data comes for them): one
     * [ModHealthBatch.KIND_HR5] row each - time = window start, value = mean, extra = `n=..;min=..;max=..`.
     */
    fun close(nowMs: Long, graceMs: Long): List<Sample> {
        val out = mutableListOf<Sample>()
        val iterator = open.entries.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next().value
            if (b.start + windowMs > nowMs - graceMs) break
            val extra = "n=${b.count};min=${b.min.roundToInt()};max=${b.max.roundToInt()}"
            out += Sample(ModHealthBatch.KIND_HR5, b.start, (b.sum / b.count).toFloat(), extra)
            closedUntil = maxOf(closedUntil, b.start + windowMs)
            iterator.remove()
        }
        return out
    }

    /** Open windows as `start:sum:count:min:max|...`. */
    fun encode(): String = open.values.joinToString("|") { "${it.start}:${it.sum}:${it.count}:${it.min}:${it.max}" }

    companion object {
        /** Open windows from [encode] and the [closedUntil] kept beside them; broken parts are skipped. */
        fun decode(text: String, closedUntil: Long = 0L, windowMs: Long = HealthConfig.HR_WINDOW_MS): HrAggregator {
            val a = HrAggregator(windowMs)
            a.closedUntil = closedUntil.coerceAtLeast(0L)
            if (text.isEmpty()) return a
            for (part in text.split('|')) {
                val p = part.split(':')
                if (p.size != 5) continue
                val start = p[0].toLongOrNull() ?: continue
                val sum = p[1].toDoubleOrNull() ?: continue
                val count = p[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                val min = p[3].toFloatOrNull() ?: continue
                val max = p[4].toFloatOrNull() ?: continue
                a.open[start] = Bucket(start, sum, count, min, max)
            }
            return a
        }
    }
}
