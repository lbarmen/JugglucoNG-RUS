package tk.glucodata.mod.watch.face

import android.content.Context

/**
 * JugglucoNGmod, watch block (part 3a): what our watch face shows right now, as raw inputs. [FaceModel.build] turns a
 * snapshot into the texts, colours and graph the face draws; all the rules (age, "no value", change, graph window)
 * live there and in [FaceValues] / [FaceGraph], under tests. A source only reads.
 *
 * [snapshot] reads upstream's stores (native history, preferences): call it off the main thread or from the
 * renderer's own thread, never from a provider's `onCreate`.
 */
interface FaceSource {
    fun snapshot(context: Context, nowMs: Long, graphHours: Int): FaceSnapshot
}

/** A glucose reading in display units ([value] mmol/L or mg/dL as the app shows it). */
data class FaceReading(
    /** Time of the reading, the phone's clock (it came from the phone or the sensor). */
    val timeMs: Long,
    val value: Float,
    /** The value as upstream formats it (their decimal separator for the locale). */
    val text: String,
    /** Trend in mg/dL per minute, as every upstream arrow takes it; NaN - no arrow. */
    val rate: Float,
    /** Upstream's range colour for [value] (ARGB). */
    val color: Int,
)

/** One point of history in display units, already on the lane the user looks at (auto or raw). */
data class FacePoint(val timeMs: Long, val value: Float)

/**
 * Everything the face needs at [nowMs] (the watch's clock).
 *
 * [current] - upstream's current reading, at most [FaceValues.SOURCE_MAX_AGE_MS] old, `null` when there is none;
 * [history] - unsmoothed history (for the change and for the time of the last reading), oldest first, at least
 * [FaceValues.HISTORY_MIN_MS] back; [graphPoints] - the same window as the user's graph smoothing draws it;
 * [low] / [high] - the user's target range in display units; batteries - percent or `null` when unknown or too old.
 */
data class FaceSnapshot(
    val nowMs: Long,
    val isMmol: Boolean,
    val hour24: Boolean,
    val graphHours: Int,
    val current: FaceReading?,
    val history: List<FacePoint>,
    val graphPoints: List<FacePoint>,
    val low: Float,
    val high: Float,
    val phoneBatteryPercent: Int?,
    val watchBatteryPercent: Int?,
)

/**
 * Which [FaceSource] the face uses: upstream's data ([ModFaceSource]) or, in a local trial build only, made-up values.
 * The trial class is compiled in only by a local build script outside this repository; builds from GitHub never
 * contain it, so the lookup fails once and the real source stays.
 */
object FaceSources {

    /** The trial source of local test builds (never in this repository). */
    internal const val TRIAL_CLASS = "tk.glucodata.mod.watch.trial.TrialFaceSource"

    @Volatile
    private var chosen: FaceSource? = null

    fun get(): FaceSource = chosen ?: find(TRIAL_CLASS).also { chosen = it }

    /** An instance of [className] when it exists and is a [FaceSource], else [ModFaceSource]. */
    internal fun find(className: String): FaceSource = try {
        Class.forName(className).getDeclaredConstructor().newInstance() as FaceSource
    } catch (_: Throwable) {
        ModFaceSource
    }
}
