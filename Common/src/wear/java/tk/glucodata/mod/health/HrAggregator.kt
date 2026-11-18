package tk.glucodata.mod.health

import java.util.TreeMap
import kotlin.math.roundToInt
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 4): heart rate as 5-minute means on the watch (as Glucove did, decision of its
 * user 21.10.2026): the sensor gives a reading about every second, the phone gets one mean per window with the count,
 * minimum and maximum. Windows are aligned to the clock; readings without skin contact and zeros are skipped. Open
 * windows are kept as a line ([encode] / [decode]) so they survive a restart of the watch process.
 */
class HrAggregator(private val windowMs: Long = HealthConfig.HR_WINDOW_MS) {

    private data class Bucket(val start: Long, val sum: Double, val count: Int, val min: Float, val max: Float)

    private val open = TreeMap<Long, Bucket>()

    val openCount: Int get() = open.size

    /** Adds one reading; [noContact] readings, zeros, negatives and NaN are skipped. */
    fun add(timeMs: Long, bpm: Float, noContact: Boolean = false) {
        if (noContact || !(bpm > 0f) || bpm.isInfinite()) return
        val start = timeMs - Math.floorMod(timeMs, windowMs)
        val b = open[start]
        open[start] = if (b == null) {
            Bucket(start, bpm.toDouble(), 1, bpm, bpm)
        } else {
            b.copy(sum = b.sum + bpm, count = b.count + 1, min = minOf(b.min, bpm), max = maxOf(b.max, bpm))
        }
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
            iterator.remove()
        }
        return out
    }

    /** Open windows as `start:sum:count:min:max|...`. */
    fun encode(): String = open.values.joinToString("|") { "${it.start}:${it.sum}:${it.count}:${it.min}:${it.max}" }

    companion object {
        /** Open windows from [encode]; broken parts are skipped. */
        fun decode(text: String, windowMs: Long = HealthConfig.HR_WINDOW_MS): HrAggregator = HrAggregator(windowMs).apply {
            if (text.isEmpty()) return@apply
            for (part in text.split('|')) {
                val p = part.split(':')
                if (p.size != 5) continue
                val start = p[0].toLongOrNull() ?: continue
                val sum = p[1].toDoubleOrNull() ?: continue
                val count = p[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                val min = p[3].toFloatOrNull() ?: continue
                val max = p[4].toFloatOrNull() ?: continue
                open[start] = Bucket(start, sum, count, min, max)
            }
        }
    }
}
