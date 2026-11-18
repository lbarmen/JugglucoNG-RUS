package tk.glucodata.mod.watch.face

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * JugglucoNGmod, watch block (part 7a): the battery icons of the face, drawn on the Canvas without images - a phone
 * (an upright rounded box) and a watch (a round body with two straps), each an outline filled from the bottom up to
 * its percent. The number beside an icon is the painter's. One instance per painter, used on its thread.
 */
internal class FaceIcons {

    enum class Kind { PHONE, WATCH }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val box = RectF()

    /** The [kind] icon [height] px tall, its left edge at [left], centred on [centerY], filled to [percent]. */
    fun draw(canvas: Canvas, kind: Kind, percent: Int, left: Float, centerY: Float, height: Float, color: Int) {
        val line = (height * LINE).coerceAtLeast(1f)
        stroke.strokeWidth = line
        stroke.color = color
        fill.color = color
        val width = width(kind, height)
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        val share = fillShare(percent)
        when (kind) {
            Kind.PHONE -> {
                box.set(left + line / 2f, top + line / 2f, left + width - line / 2f, bottom - line / 2f)
                val corner = width * 0.22f
                canvas.drawRoundRect(box, corner, corner, stroke)
                val inset = line * 1.6f
                val innerTop = top + inset
                val innerBottom = bottom - inset
                val level = innerBottom - (innerBottom - innerTop) * share
                if (share > 0f) canvas.drawRect(left + inset, level, left + width - inset, innerBottom, fill)
            }
            Kind.WATCH -> {
                val cx = left + width / 2f
                val radius = width / 2f - line / 2f
                val strap = width * 0.56f
                // the straps above and below the body
                canvas.drawRect(cx - strap / 2f, top, cx + strap / 2f, centerY - radius + line, fill)
                canvas.drawRect(cx - strap / 2f, centerY + radius - line, cx + strap / 2f, bottom, fill)
                canvas.drawCircle(cx, centerY, radius, stroke)
                val inner = radius - line * 1.1f
                if (share > 0f && inner > 0f) {
                    canvas.save()
                    path.reset()
                    path.addCircle(cx, centerY, inner, Path.Direction.CW)
                    canvas.clipPath(path)
                    val level = centerY + inner - 2f * inner * share
                    canvas.drawRect(cx - inner, level, cx + inner, centerY + inner, fill)
                    canvas.restore()
                }
            }
        }
    }

    companion object {
        /** At this percent or less the icon and its number are red, and the dim mode still shows them. */
        const val LOW_PERCENT = 20

        /** The outline's weight, share of the icon's height. */
        private const val LINE = 0.09f

        /** The icon's width for its [height]. */
        fun width(kind: Kind, height: Float): Float = height * if (kind == Kind.PHONE) 0.55f else 0.62f

        /** A battery has an icon: its percent is known and, in the dim mode, low. */
        fun shown(percent: Int?, ambient: Boolean): Boolean =
            percent != null && percent in 0..100 && (!ambient || isLow(percent))

        fun isLow(percent: Int): Boolean = percent <= LOW_PERCENT

        /** How much of the icon is filled, 0..1. */
        fun fillShare(percent: Int): Float = percent.coerceIn(0, 100) / 100f
    }
}
