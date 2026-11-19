package tk.glucodata.mod.watch.face

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.glucosecomplication.ComplicationRenderer
import tk.glucodata.mod.watch.ModWatchLog
import tk.glucodata.mod.watch.SignalBudget

/**
 * JugglucoNGmod, watch block (parts 3b, 3c): draws our face once a minute (upstream's face: the same 60 s frame),
 * again when the watch leaves the dim mode (the wrist raised), when a new reading arrives (upstream's public
 * `UiRefreshBus.revision`, no hook) and when the graph hours change in our editor ([FaceSettings]).
 *
 * The data ([FaceSource.snapshot]: history, the current value, settings) is heavy, so it is read on a background
 * thread and kept; [render] on the main thread only builds the [FaceModel] for this minute from the kept snapshot
 * and paints it. A kept snapshot older than [STALE_MS] asks for a new one at the next frame. A new reading is read at
 * once, up to [READS_PER_WINDOW] reads in any [READ_WINDOW_MS] (part 7o: the phone's calibration and then its values
 * come a moment apart each minute; the old "read, then a pause of 20 s" left the value 20 s behind the phone); over
 * that budget (history catching up) the read waits until the oldest read leaves the window, and never while the face
 * is not visible (the read waits until it is). The face is drawn again only when what it shows changed - not for the graph window
 * moving on, so the dim mode keeps one frame a minute. Our failure never takes the watch face process down: every
 * step is guarded and the coroutines have a handler that swallows what escapes.
 *
 * The dim mode (part 7c, critic 7, S2 and N8): a kept snapshot there is read again only after [AMBIENT_STALE_MS]
 * (new readings still come through [request]), the picture moves one step a minute ([FaceLayout.burnInOffset]). Its
 * snapshots hold the forecast as well (part 7l: the dim mode draws it). The dots' colours come from the limits kept
 * with the snapshot, not from native calls in every frame (critic 7, N7). The dim mode draws only on the system's
 * minute tick (`postInvalidate` draws nothing there, watchface 1.2.1), so a frame that finds a signal newer than its
 * kept snapshot (the read not done yet or waiting for the budget) reads the snapshot itself first (part 7o).
 *
 * The journal (part 7o, [ModWatchLog], no values, no times of events): `face read` for every read, `face frame` for
 * the first frame of a new reading - the reading's age in seconds, the read's duration, the mode.
 */
internal class ModFaceRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    private val watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository,
) : Renderer.CanvasRenderer2<ModFaceRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    FRAME_MS,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
) {
    class Assets : SharedAssets {
        override fun onDestroy() {}
    }

    override suspend fun createSharedAssets(): Assets = Assets()

    /**
     * A snapshot with the labels of its time, when it was read (the watch's clock) and the traffic light's limits;
     * [signal] - the count of [request]s when its read began (a later request: data it may not hold), [order] - the
     * read's place among all reads (a read that began later is never replaced by one that began earlier).
     */
    private class Kept(
        val snapshot: FaceSnapshot,
        val labels: FaceLabels,
        val readAtMs: Long,
        val limits: ModFaceSource.Limits?,
        val signal: Long,
        val order: Long,
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t ->
            ModWatchLog.w("face coroutine failed: ${t.javaClass.simpleName}")
        },
    )
    private val painter = FacePainter()
    private val refreshing = AtomicBoolean(false)

    @Volatile private var again = false
    @Volatile private var kept: Kept? = null
    @Volatile private var graphHours = FaceGraph.DEFAULT_HOURS

    /** New data waits for a read ([request]); the read clears it. */
    @Volatile private var dirty = false

    /** How many [request]s there were (written on the main thread only). */
    @Volatile private var signals = 0L
    private val reads = AtomicLong()
    private val keptLock = Any()
    private val budget = SignalBudget(READS_PER_WINDOW, READ_WINDOW_MS)
    private val pause = ReadPause(scope)

    /** The time of the reading the journal's last `face frame` line was about. */
    private var framedReadingMs = Long.MIN_VALUE

    /** Kept as a field: preferences hold their listeners weakly. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FaceSettings.KEY_GRAPH_HOURS) refresh()
    }

    /** The layout of the last frame, for the tap on the value. */
    @Volatile var lastLayout: FaceLayout.Layout? = null
        private set

    init {
        guarded {
            // leaving the dim mode: as new data (its snapshot may be up to AMBIENT_STALE_MS old)
            scope.launch { watchState.isAmbient.collect { ambient -> if (ambient == false) request() } }
            scope.launch { watchState.isVisible.collect { visible -> if (visible == true && dirty) request() } }
            scope.launch { UiRefreshBus.revision.drop(1).collect { request() } }
            guarded { FaceSettings.prefs(context).registerOnSharedPreferenceChangeListener(settingsListener) }
            refresh()
        }
    }

    /** New data (main thread): a read now, once the budget allows it, or once the face is visible again. */
    private fun request() {
        signals++
        dirty = true
        val wait = readWait(watchState.isVisible.value == true, budget.lastRuns(), SystemClock.elapsedRealtime())
            ?: return
        if (wait <= 0L) refresh()
        else pause.arm(wait) { if (dirty) request() }
    }

    /** Reads a new snapshot in the background; a request during a read makes one more read after it. */
    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            again = true
            return
        }
        scope.launch(Dispatchers.Default) {
            try {
                do {
                    again = false
                    load()
                } while (again)
            } finally {
                refreshing.set(false)
            }
            if (again) refresh()
        }
    }

    /**
     * Reads a snapshot and keeps it. [inFrame] - called from [render] (the dim mode): no `postInvalidate`, the frame
     * draws what this read kept.
     */
    private fun load(inFrame: Boolean = false) {
        val signal = signals
        val order = reads.incrementAndGet()
        dirty = false
        val begin = SystemClock.elapsedRealtime()
        budget.record(begin)
        val now = System.currentTimeMillis()
        val hours = FaceSettings.hours(context)
        graphHours = hours
        val next = guarded {
            val snapshot = FaceSources.get().snapshot(context, now, hours)
            Kept(snapshot, FaceLabels.from(context), now, guarded { ModFaceSource.limits(snapshot.isMmol) }, signal,
                order)
        } ?: return
        val old = synchronized(keptLock) {
            val prev = kept
            // a read that began later (the frame's own, or the next background one) is kept already
            if (prev != null && prev.order > next.order) return
            kept = next
            prev
        }
        val changed = old == null || old.labels != next.labels || shown(old.snapshot) != shown(next.snapshot)
        ModWatchLog.i("face read age=${ageText(next.snapshot.current, now)} took=${SystemClock.elapsedRealtime() - begin}" +
            " ms changed=$changed amb=${watchState.isAmbient.value == true}" + if (inFrame) " in-frame" else "")
        if (changed && !inFrame) guarded { postInvalidate() }
    }

    /** A request came after the read of [data] began: it may not hold the newest data. */
    private fun behind(data: Kept?): Boolean = signals > (data?.signal ?: 0L)

    /** What the face shows from a snapshot, apart from the graph window moving on with the clock. */
    private fun shown(s: FaceSnapshot) = listOf(s.isMmol, s.hour24, s.graphHours, s.current, s.history.lastOrNull(),
        s.graphPoints.lastOrNull(), s.low, s.high, s.phoneBatteryPercent, s.watchBatteryPercent, s.forecast.lastOrNull())

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        try {
            val now = zonedDateTime.toInstant().toEpochMilli()
            val ambient = renderParameters.drawMode == DrawMode.AMBIENT
            // part 7o: the dim mode's next frame is a minute away - what has arrived is read for this one; a failed
            // read leaves the old snapshot, drawn as before
            if (ambient && behind(kept)) guarded { load(inFrame = true) }
            val data = kept
            if (data == null || abs(now - data.readAtMs) >= staleAfter(ambient)) refresh()
            data?.snapshot?.current?.let { reading ->
                if (reading.timeMs != framedReadingMs) {
                    framedReadingMs = reading.timeMs
                    ModWatchLog.i("face frame age=${ageText(reading, now)} amb=$ambient")
                }
            }
            val isMmol = data?.snapshot?.isMmol ?: ComplicationRenderer.isMmol()
            val snapshot = data?.snapshot?.copy(nowMs = now) ?: FaceSnapshot(now, isMmol, true, graphHours, null,
                emptyList(), emptyList(), 0f, 0f, null, null)
            val labels = data?.labels ?: FaceLabels.from(context)
            val model = FaceModel.build(snapshot, zonedDateTime.zone, labels)
            val limits = data?.limits
            val shift = if (ambient) {
                FaceLayout.burnInOffset(Math.floorDiv(now, 60_000L), minOf(bounds.width(), bounds.height()).toFloat(),
                    strong = watchState.hasBurnInProtection)
            } else null
            lastLayout = painter.draw(
                canvas, bounds.width(), bounds.height(), model, isMmol,
                ambient = ambient,
                showData = data != null,
                trial = FaceSources.get() !== ModFaceSource,
                // every dot as the app would paint its value (part 7a), whatever upstream's range colours setting
                traceColor = { value ->
                    limits?.let { guarded { ModFaceSource.trafficColor(value, isMmol, it) } } ?: TRACE
                },
                offsetX = shift?.first ?: 0f,
                offsetY = shift?.second ?: 0f,
            )
        } catch (_: Throwable) {
            guarded { canvas.drawColor(FaceStyle.BLACK) }
        }
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        guarded { canvas.drawColor(FaceStyle.BLACK) }
    }

    override fun onDestroy() {
        guarded { FaceSettings.prefs(context).unregisterOnSharedPreferenceChangeListener(settingsListener) }
        guarded { scope.cancel() }
        super.onDestroy()
    }

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** One frame a minute in interactive mode, as upstream's face (`WatchRenderer` FRAME_PERIOD_MS_DEFAULT). */
        const val FRAME_MS = 60_000L

        /**
         * A kept snapshot this old is read again at the next frame. That read does not go through [request]; it adds
         * at most one read to a window of [READ_WINDOW_MS] because it is never shorter than it (critic 3c, N2; a test
         * holds it), and it counts in the budget like every read.
         */
        const val STALE_MS = 50_000L

        /**
         * The same in the dim mode (part 7c, critic 7, N8): new readings come through [request] there as well, so the
         * read of a frame is only a backstop for what arrives without one (the batteries, the labels).
         */
        const val AMBIENT_STALE_MS = 5 * 60_000L

        /** How old a kept snapshot may get before a frame reads a new one. */
        internal fun staleAfter(ambient: Boolean): Long = if (ambient) AMBIENT_STALE_MS else STALE_MS

        /**
         * The read budget (part 7o): at most [READS_PER_WINDOW] reads in any [READ_WINDOW_MS] - history arrives in
         * dozens of pieces while it catches up; a normal minute (calibration, then values) takes two.
         */
        const val READ_WINDOW_MS = SignalBudget.WINDOW_MS
        const val READS_PER_WINDOW = SignalBudget.COUNT

        /**
         * What a request for new data does: `null` - nothing until the face is [visible] again (the request stays
         * pending); 0 - read now; more - read after that many ms, when the oldest of [lastReadsMs] (the times of the
         * last [READS_PER_WINDOW] reads, [SignalBudget.NEVER] - none) leaves the window of [READ_WINDOW_MS]
         * ([nowMs] on the same clock).
         */
        internal fun readWait(visible: Boolean, lastReadsMs: LongArray, nowMs: Long): Long? =
            if (!visible) null else SignalBudget.waitFor(lastReadsMs, nowMs, READ_WINDOW_MS)

        /** The age of [reading] at [nowMs] for the journal: whole seconds, or `none`. */
        internal fun ageText(reading: FaceReading?, nowMs: Long): String =
            reading?.let { "${(nowMs - it.timeMs) / 1000L} s" } ?: "none"

        /** The graph's tone where upstream gives no range colour. */
        private const val TRACE = 0xFFE3E2DE.toInt()
    }
}

/**
 * The one pending "read after the pause" of [ModFaceRenderer]: [arm] sets it unless one is waiting. When its wait is
 * over the job clears itself before [then] runs - inside itself it is still "active", and a request made from [then]
 * must be able to set the next pause (critic 3c, N1; a test holds it, critic 5, N-5-9). Used on one thread (the
 * renderer's main scope).
 */
internal class ReadPause(private val scope: CoroutineScope) {
    private var job: Job? = null

    val waiting: Boolean get() = job?.isActive == true

    fun arm(waitMs: Long, then: () -> Unit) {
        if (waiting) return
        job = scope.launch {
            delay(waitMs)
            job = null
            then()
        }
    }
}
