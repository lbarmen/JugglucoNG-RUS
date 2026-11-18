package tk.glucodata.mod.watch.face

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JugglucoNGmod, watch block (part 3a, the whole circle since part 7a, the status row at the bottom since part 7c):
 * where the face's rows sit on a round screen, without a Canvas:
 * ```
 *                 19:57              time alone at the top (~50 px on 450)
 *          10,8 ↗  +0.5              value (~90 px on 450), arrow, change over age
 *                  2 min
 *   - - - - - - - - - - - - - - - -  target lines to the round edge
 *   10.0  ·  ·· ·  ·   ··· ·  ·::  ╌ 5-minute dots, forecast dashed on the right
 *   3.9 - - - - - - - - - - - - - -
 *          18      19      20        hour labels under the hour lines
 *         [▯] 49  Wed 18  [⌚] 81     batteries as icons, the weekday and day, above the round edge
 * ```
 * Every share is of the screen's diameter (the smaller side, centred); a row is as wide as the circle allows at its
 * edge farther from the centre, less a margin, so nothing reaches the round edge on any size. Part 7c (the owner's
 * word) gave up the free band at the bottom that was kept for the system's ongoing-activity icon: our watch build
 * makes that icon a faint speck (`wearMod/res/drawable-anydpi-v24/novalue.xml`) and the status row takes the room.
 */
object FaceLayout {

    /** Top and bottom of each row, shares of the diameter from the top. */
    private const val TIME_TOP = 0.065f
    private const val TIME_BOTTOM = 0.19f
    private const val VALUE_TOP = 0.20f
    private const val VALUE_BOTTOM = 0.40f
    private const val GRAPH_TOP = 0.415f
    private const val GRAPH_BOTTOM = 0.80f
    /** The hour labels under the graph's hour lines (part 3c2). */
    private const val HOURS_TOP = 0.806f
    private const val HOURS_BOTTOM = 0.849f
    /** The batteries and the date (at the bottom since part 7c). */
    const val STATUS_TOP = 0.858f
    const val STATUS_BOTTOM = 0.908f
    /** The "TEST" mark of a local trial build (part 3b), under the status row. */
    private const val MARK_TOP = 0.914f
    private const val MARK_BOTTOM = 0.95f

    /** Text sizes, shares of the diameter. */
    const val TIME_TEXT = 0.11f
    private const val STATUS_TEXT = 0.045f
    private const val VALUE_TEXT = 0.20f
    private const val ARROW = 0.10f
    private const val SIDE_TEXT = 0.058f
    private const val GRAPH_LABEL_TEXT = 0.038f
    private const val HOURS_TEXT = 0.035f
    private const val MARK_TEXT = 0.035f

    /** The tap area of the value reaches this share of the value row's height above and below it. */
    private const val TAP_SLACK = 0.05f

    /** Space kept from the round edge on each side, share of the diameter. */
    const val MARGIN = 0.04f

    /**
     * A generous average glyph width, share of the text size, for checking that a text fits without measuring it
     * (digits and letters of the face's fonts are narrower); the renderer measures for real.
     */
    const val CHAR_WIDTH = 0.6f

    /** One row in pixels: its box and its text size. */
    data class Row(val left: Float, val top: Float, val right: Float, val bottom: Float, val textSize: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * The face on a [widthPx] x [heightPx] screen: the rows top to bottom; in the value row the number takes
     * [Row.textSize], the arrow [arrowSize] and the change and age, one over the other, [sideTextSize].
     */
    data class Layout(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val time: Row,
        val value: Row,
        val arrowSize: Float,
        val sideTextSize: Float,
        val graph: Row,
        val graphLabelSize: Float,
        /** The labels of the graph's hour lines (part 3c2). */
        val hours: Row,
        /** The battery icons with their numbers and, between them, the weekday and day (part 7a; the bottom, 7c). */
        val status: Row,
        val mark: Row,
    ) {
        /** The drawn rows, top to bottom. */
        val rows: List<Row> get() = listOf(time, value, graph, hours, status, mark)
    }

    /** A thin hour line of the graph at [x] (pixels) and its [label]. */
    data class HourLine(val x: Float, val label: String)

    /**
     * Where the value row's parts start (left edges, pixels) once the whole is centred in the row, and the [scale]
     * every part's size is multiplied by when the whole would be wider than the row (1 when it fits).
     */
    data class ValuePlacement(val scale: Float, val valueX: Float, val arrowX: Float, val sideX: Float)

    /**
     * Left edges in the status row: the date centred at [dateX], the phone's battery (icon and number) ending a gap
     * left of it at [phoneX], the watch's starting a gap right of it at [watchX].
     */
    data class StatusPlacement(val phoneX: Float, val dateX: Float, val watchX: Float)

    fun compute(widthPx: Int, heightPx: Int): Layout {
        val size = min(widthPx, heightPx).toFloat().coerceAtLeast(1f)
        val left = (widthPx - size) / 2f
        val top = (heightPx - size) / 2f
        fun row(rowTop: Float, rowBottom: Float, text: Float): Row {
            // The edge farther from the centre is the narrower one.
            val far = maxOf(abs(rowTop - 0.5f), abs(rowBottom - 0.5f))
            val half = (sqrt((0.25f - far * far).coerceAtLeast(0f)) - MARGIN).coerceAtLeast(0f)
            return Row(left + (0.5f - half) * size, top + rowTop * size, left + (0.5f + half) * size,
                top + rowBottom * size, text * size)
        }
        return Layout(
            centerX = left + size / 2f,
            centerY = top + size / 2f,
            radius = size / 2f,
            time = row(TIME_TOP, TIME_BOTTOM, TIME_TEXT),
            value = row(VALUE_TOP, VALUE_BOTTOM, VALUE_TEXT),
            arrowSize = ARROW * size,
            sideTextSize = SIDE_TEXT * size,
            graph = row(GRAPH_TOP, GRAPH_BOTTOM, GRAPH_LABEL_TEXT),
            graphLabelSize = GRAPH_LABEL_TEXT * size,
            hours = row(HOURS_TOP, HOURS_BOTTOM, HOURS_TEXT),
            status = row(STATUS_TOP, STATUS_BOTTOM, STATUS_TEXT),
            mark = row(MARK_TOP, MARK_BOTTOM, MARK_TEXT),
        )
    }

    /** The steps of [burnInOffset]: the picture where it is, then right, down, left and up (part 7c). */
    private val BURN_IN_STEPS = listOf(0 to 0, 1 to 0, 0 to 1, -1 to 0, 0 to -1)

    /** How far [burnInOffset] moves the picture, share of the diameter (~3.6 px on 450). */
    const val BURN_IN_SHIFT = 0.008f

    /**
     * Where the dim mode moves the whole picture in [minute] (minutes since the epoch), pixels on a [diameter] screen
     * (part 7c, critic 7, S2): one of five places, the next every minute, round and round, so no pixel of the number
     * or of a line stays lit in one place all day. [strong] - the watch says its screen needs burn-in protection
     * (`WatchState.hasBurnInProtection`): twice as far. Whole pixels. Every row keeps [MARGIN] from the round edge and the target
     * lines 0.03 of the diameter, so the moved picture stays inside the circle.
     */
    fun burnInOffset(minute: Long, diameter: Float, strong: Boolean): Pair<Float, Float> {
        val (dx, dy) = BURN_IN_STEPS[Math.floorMod(minute, BURN_IN_STEPS.size.toLong()).toInt()]
        // whole pixels, so the moved texts stay as sharp as the unmoved ones
        val step = Math.round(diameter * BURN_IN_SHIFT).coerceAtLeast(1) * (if (strong) 2f else 1f)
        return dx * step to dy * step
    }

    /**
     * Half the width of the circle at height [y] (pixels) less [inset] at each end: a target line drawn from
     * `centerX - halfWidthAt` to `centerX + halfWidthAt` reaches the round edge without touching it.
     */
    fun halfWidthAt(layout: Layout, y: Float, inset: Float): Float {
        val dy = y - layout.centerY
        return (sqrt((layout.radius * layout.radius - dy * dy).coerceAtLeast(0f)) - inset).coerceAtLeast(0f)
    }

    /**
     * The value row of measured widths: the number, the arrow (0 - none) and the wider of change and age (0 - none),
     * [gap] between present parts; centred, scaled down together when wider than the row.
     */
    fun placeValueRow(row: Row, valueWidth: Float, arrowWidth: Float, sideWidth: Float, gap: Float): ValuePlacement {
        val gaps = (if (arrowWidth > 0f) gap else 0f) + (if (sideWidth > 0f) gap else 0f)
        val total = valueWidth + arrowWidth + sideWidth + gaps
        val scale = if (total > row.width && total > 0f) (row.width / total).coerceAtLeast(0f) else 1f
        val left = row.centerX - total * scale / 2f
        val arrowX = left + (valueWidth + (if (arrowWidth > 0f) gap else 0f)) * scale
        val sideX = arrowX + (arrowWidth + (if (sideWidth > 0f) gap else 0f)) * scale
        return ValuePlacement(scale, left, arrowX, sideX)
    }

    /**
     * The status row of measured widths ([phoneWidth] / [watchWidth]: icon, gap and number; 0 - not shown): the date
     * centred, the batteries [gap] beside it. A battery that would reach past the row is pushed back inside.
     */
    fun placeStatus(row: Row, dateWidth: Float, phoneWidth: Float, watchWidth: Float, gap: Float): StatusPlacement {
        val dateX = row.centerX - dateWidth / 2f
        val phoneX = (dateX - gap - phoneWidth).coerceAtLeast(row.left)
        val watchX = (dateX + dateWidth + gap).coerceAtMost(row.right - watchWidth)
        return StatusPlacement(phoneX, dateX, watchX)
    }

    /**
     * The graph's hour lines: each of [marks] at its time in the [plot]'s window, between [plotLeft] and [plotRight]
     * (pixels).
     */
    fun hourLines(plot: FaceGraph.Plot, marks: List<FaceGraph.HourMark>, plotLeft: Float, plotRight: Float):
        List<HourLine> = marks.map { HourLine(plotLeft + FaceGraph.xFraction(plot, it.timeMs) * (plotRight - plotLeft), it.label) }

    /**
     * Left edges of the hour labels in the [hours] row, each centred under its line ([widths] - measured, same
     * order as [lines]); `null` - the label is left out (its line stays): it would reach past the row (the round
     * edge) or come closer than [gap] to the label before it. The row lies between the graph and the status row,
     * so a label never touches either.
     */
    fun placeHourLabels(hours: Row, lines: List<HourLine>, widths: List<Float>, gap: Float): List<Float?> {
        var lastRight = Float.NEGATIVE_INFINITY
        return lines.mapIndexed { i, line ->
            val width = widths.getOrElse(i) { 0f }
            val left = line.x - width / 2f
            val right = left + width
            if (width <= 0f || left < hours.left || right > hours.right || left < lastRight + gap) null
            else {
                lastRight = right
                left
            }
        }
    }

    /** A tap at [x], [y] (pixels) is on the value: the value row, a little taller, across its width. */
    fun isOnValue(layout: Layout, x: Float, y: Float): Boolean {
        val row = layout.value
        val slack = row.height * TAP_SLACK
        return x >= row.left && x <= row.right && y >= row.top - slack && y <= row.bottom + slack
    }

    /** A width estimate for [text] at [textSize] (see [CHAR_WIDTH]). */
    fun estimatedWidth(text: String, textSize: Float): Float = text.length * CHAR_WIDTH * textSize

    /**
     * The value row's content width estimate: the number, the arrow and the wider of change and age, with the
     * gaps the renderer leaves between them.
     */
    fun valueRowWidth(layout: Layout, valueText: String, deltaText: String, ageText: String): Float {
        val gap = layout.radius * 0.04f
        return estimatedWidth(valueText, layout.value.textSize) + gap + layout.arrowSize + gap +
            maxOf(estimatedWidth(deltaText, layout.sideTextSize), estimatedWidth(ageText, layout.sideTextSize))
    }
}
