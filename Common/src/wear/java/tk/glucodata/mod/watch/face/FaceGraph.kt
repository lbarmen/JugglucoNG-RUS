package tk.glucodata.mod.watch.face

/**
 * JugglucoNGmod, watch block (part 3a): the face's graph without drawing - the time window (1, 3 or 6 hours, a face
 * setting; 3 by default) and the vertical scale. The target range lines are always in view, so a calm in-range trace
 * still shows where "low" and "high" are, and a trace far above them is never cut off.
 */
object FaceGraph {

    val HOURS = listOf(1, 3, 6)
    const val DEFAULT_HOURS = 3

    /** Upstream's least span of the vertical scale (their sparkline): a calm hour must not look like mountains. */
    private const val MIN_SPAN_MMOL = 3f
    private const val MIN_SPAN_MGDL = 54f

    /** Air above and below the extremes, a share of the span, at least [MIN_PAD_MMOL] / [MIN_PAD_MGDL]. */
    private const val PAD_SHARE = 0.08f
    private const val MIN_PAD_MMOL = 0.3f
    private const val MIN_PAD_MGDL = 5f

    /**
     * What to draw: [points] inside [fromMs]..[toMs], oldest first; the vertical scale [bottom]..[top] (display
     * units) holds every point and both [low] and [high].
     */
    data class Plot(
        val fromMs: Long,
        val toMs: Long,
        val points: List<FacePoint>,
        val bottom: Float,
        val top: Float,
        val low: Float,
        val high: Float,
    )

    /** A setting value as one of [HOURS]; anything else is [DEFAULT_HOURS]. */
    fun sanitizeHours(hours: Int): Int = if (hours in HOURS) hours else DEFAULT_HOURS

    fun windowMs(hours: Int): Long = sanitizeHours(hours) * 3_600_000L

    /**
     * The plot of [points] for the [hours] up to [nowMs]: points before the window and unusable values are left out;
     * points a little ahead of [nowMs] (the phone's clock ahead of the watch's) stay and sit at the right edge.
     */
    fun plot(points: List<FacePoint>, nowMs: Long, hours: Int, low: Float, high: Float, isMmol: Boolean): Plot {
        val from = nowMs - windowMs(hours)
        val inWindow = points.filter { it.timeMs >= from && FaceValues.usable(it.value) }.sortedBy { it.timeMs }
        val lo = minOf(low, high)
        val hi = maxOf(low, high)
        var minValue = minOf(lo, inWindow.minOfOrNull { it.value } ?: lo)
        var maxValue = maxOf(hi, inWindow.maxOfOrNull { it.value } ?: hi)
        val minSpan = if (isMmol) MIN_SPAN_MMOL else MIN_SPAN_MGDL
        if (maxValue - minValue < minSpan) {
            val mid = (maxValue + minValue) / 2f
            minValue = mid - minSpan / 2f
            maxValue = mid + minSpan / 2f
        }
        val pad = maxOf((maxValue - minValue) * PAD_SHARE, if (isMmol) MIN_PAD_MMOL else MIN_PAD_MGDL)
        val bottom = maxOf(0f, minValue - pad)
        return Plot(from, nowMs, inWindow, bottom, maxValue + pad, lo, hi)
    }

    /** Horizontal place of [timeMs] in the plot, 0 (left, oldest) .. 1 (right, now); clamped to the box. */
    fun xFraction(plot: Plot, timeMs: Long): Float {
        val span = (plot.toMs - plot.fromMs).coerceAtLeast(1L)
        return ((timeMs - plot.fromMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Vertical place of [value] in the plot, 0 (top) .. 1 (bottom); clamped to the box. */
    fun yFraction(plot: Plot, value: Float): Float {
        val span = (plot.top - plot.bottom).coerceAtLeast(0.001f)
        return (1f - (value - plot.bottom) / span).coerceIn(0f, 1f)
    }
}
