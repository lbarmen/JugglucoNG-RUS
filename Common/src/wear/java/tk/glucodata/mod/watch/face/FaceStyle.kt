package tk.glucodata.mod.watch.face

import java.util.Locale

/**
 * JugglucoNGmod, watch block (part 3b): colours and line weights of the face, pure values (watch plan, "Watch face").
 *
 * The background is always black (the screen stays on, AMOLED). The dim (ambient) mode keeps the same picture:
 * every colour dimmer by [AMBIENT_DIM], thinner lines and no fills (the target band and the graph frame go), the hour
 * lines without their labels.
 * Widths are shares of the screen's diameter; [arrowStroke] is a share of the arrow's size.
 */
data class FaceStyle(
    val ambient: Boolean,
    val background: Int,
    val time: Int,
    /** Date, change, batteries and the graph's range labels. */
    val secondary: Int,
    val age: Int,
    /** The age after [FaceValues.AGE_ALARM_MINUTES]. */
    val ageAlarm: Int,
    /** The "-" without a value, and the tone of a value without a range colour. */
    val neutral: Int,
    val rangeLine: Int,
    /** The target band behind the graph; `null` - no fill. */
    val rangeFill: Int?,
    /** The graph's frame; `null` - no frame. */
    val frame: Int?,
    /** The "TEST" mark of a local trial build with made-up values. */
    val mark: Int,
    val traceWidth: Float,
    val rangeLineWidth: Float,
    val frameWidth: Float,
    val arrowStroke: Float,
    /** The graph's thin lines at full hours (part 3c2). */
    val hourLine: Int = HOUR_LINE,
    val hourLineWidth: Float = 0.0025f,
    /** The hour labels under those lines; `null` - none (the dim mode keeps the lines only). */
    val hourLabel: Int? = HOUR_LABEL,
) {
    /** The value's own range colour (upstream's), dimmed in ambient; [neutral] without one. */
    fun valueColor(rangeColor: Int?): Int = rangeColor?.let { tone(it) } ?: neutral

    /** A graph point's range colour, dimmed in ambient. */
    fun traceColor(rangeColor: Int): Int = tone(rangeColor)

    private fun tone(color: Int): Int = if (ambient) dim(color, AMBIENT_DIM) else color

    companion object {
        const val BLACK = 0xFF000000.toInt()

        /** How much of each colour channel the ambient mode keeps. */
        const val AMBIENT_DIM = 0.6f

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFFB4B4B4.toInt()
        private const val RED = 0xFFFF5252.toInt()
        /** Upstream's neutral value tone on a dark face (as [ModFaceSource]). */
        private const val NEUTRAL = 0xFFE3E2DE.toInt()
        private const val RANGE_LINE = 0xFF7A7A7A.toInt()
        /** A faint green band between the target lines. */
        private const val RANGE_FILL = 0x2E4CAF50
        private const val FRAME = 0xFF333333.toInt()
        private const val ORANGE = 0xFFFF9800.toInt()
        /** Hour lines: fainter than the target lines, so they mark time without competing with the range. */
        private const val HOUR_LINE = 0xFF4A4A4A.toInt()
        private const val HOUR_LABEL = 0xFF9A9A9A.toInt()

        private val NORMAL = FaceStyle(
            ambient = false, background = BLACK, time = WHITE, secondary = GREY, age = GREY, ageAlarm = RED,
            neutral = NEUTRAL, rangeLine = RANGE_LINE, rangeFill = RANGE_FILL, frame = FRAME, mark = ORANGE,
            traceWidth = 0.012f, rangeLineWidth = 0.004f, frameWidth = 0.003f, arrowStroke = 0.12f,
        )

        private val AMBIENT = FaceStyle(
            ambient = true, background = BLACK, time = dim(WHITE, AMBIENT_DIM), secondary = dim(GREY, AMBIENT_DIM),
            age = dim(GREY, AMBIENT_DIM), ageAlarm = dim(RED, AMBIENT_DIM), neutral = dim(NEUTRAL, AMBIENT_DIM),
            rangeLine = dim(RANGE_LINE, AMBIENT_DIM), rangeFill = null, frame = null, mark = dim(ORANGE, AMBIENT_DIM),
            traceWidth = 0.006f, rangeLineWidth = 0.0025f, frameWidth = 0f, arrowStroke = 0.07f,
            hourLine = dim(HOUR_LINE, AMBIENT_DIM), hourLineWidth = 0.002f, hourLabel = null,
        )

        fun of(ambient: Boolean): FaceStyle = if (ambient) AMBIENT else NORMAL

        /** [color] with each of red, green and blue scaled by [factor] (0..1); alpha kept. */
        fun dim(color: Int, factor: Float): Int {
            val f = factor.coerceIn(0f, 1f)
            fun channel(shift: Int) = ((((color ushr shift) and 0xFF) * f) + 0.5f).toInt().coerceIn(0, 255) shl shift
            return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
        }

        /** A target line's label as the app writes values: "10.0" / "10,0" in mmol/L, "180" in mg/dL. */
        fun rangeLabel(value: Float, isMmol: Boolean, locale: Locale = Locale.getDefault()): String =
            String.format(locale, if (isMmol) "%.1f" else "%.0f", value)
    }
}
