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

/**
 * JugglucoNGmod, watch block (parts 3b, 3c): draws our face once a minute (upstream's face: the same 60 s frame),
 * again when the watch leaves the dim mode (the wrist raised), when a new reading arrives (upstream's public
 * `UiRefreshBus.revision`, no hook) and when the graph hours change in our editor ([FaceSettings]).
 *
 * The data ([FaceSource.snapshot]: history, the current value, settings) is heavy, so it is read on a background
 * thread and kept; [render] on the main thread only builds the [FaceModel] for this minute from the kept snapshot
 * and paints it. A kept snapshot older than [STALE_MS] asks for a new one at the next frame. A new reading asks for a
 * read at most once per [MIN_READ_MS] (one more at the end of the pause) and never while the face is not visible
 * (the read waits until it is). The face is drawn again only when what it shows changed - not for the graph window
 * moving on, so the dim mode keeps one frame a minute. Our failure never takes the watch face process down: every
 * step is guarded and the coroutines have a handler that swallows what escapes.
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

    /** A snapshot with the labels of its time and when it was read (the watch's clock). */
    private class Kept(val snapshot: FaceSnapshot, val labels: FaceLabels, val readAtMs: Long)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> },
    )
    private val painter = FacePainter()
    private val refreshing = AtomicBoolean(false)

    @Volatile private var again = false
    @Volatile private var kept: Kept? = null
    @Volatile private var graphHours = FaceGraph.DEFAULT_HOURS

    /** New data waits for a read ([request]); the read clears it. */
    @Volatile private var dirty = false
    @Volatile private var lastReadAt = Long.MIN_VALUE / 2
    private var pause: Job? = null

    /** Kept as a field: preferences hold their listeners weakly. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FaceSettings.KEY_GRAPH_HOURS) refresh()
    }

    /** The layout of the last frame, for the tap on the value. */
    @Volatile var lastLayout: FaceLayout.Layout? = null
        private set

    init {
        guarded {
            scope.launch { watchState.isAmbient.collect { ambient -> if (ambient == false) request() } }
            scope.launch { watchState.isVisible.collect { visible -> if (visible == true && dirty) request() } }
            scope.launch { UiRefreshBus.revision.drop(1).collect { request() } }
            guarded { FaceSettings.prefs(context).registerOnSharedPreferenceChangeListener(settingsListener) }
            refresh()
        }
    }

    /** New data (main thread): a read now, after the pause of [MIN_READ_MS], or once the face is visible again. */
    private fun request() {
        dirty = true
        if (watchState.isVisible.value != true) return
        val wait = lastReadAt + MIN_READ_MS - SystemClock.elapsedRealtime()
        if (wait <= 0) refresh()
        else if (pause?.isActive != true) pause = scope.launch { delay(wait); if (dirty) request() }
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

    private fun load() {
        dirty = false
        lastReadAt = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val hours = FaceSettings.hours(context)
        graphHours = hours
        val next = guarded {
            Kept(FaceSources.get().snapshot(context, now, hours), FaceLabels.from(context), now)
        } ?: return
        val old = kept
        kept = next
        if (old == null || old.labels != next.labels || shown(old.snapshot) != shown(next.snapshot)) {
            guarded { postInvalidate() }
        }
    }

    /** What the face shows from a snapshot, apart from the graph window moving on with the clock. */
    private fun shown(s: FaceSnapshot) = listOf(s.isMmol, s.hour24, s.graphHours, s.current, s.history.lastOrNull(),
        s.graphPoints.lastOrNull(), s.low, s.high, s.phoneBatteryPercent, s.watchBatteryPercent)

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        try {
            val now = zonedDateTime.toInstant().toEpochMilli()
            val data = kept
            if (data == null || abs(now - data.readAtMs) >= STALE_MS) refresh()
            val isMmol = data?.snapshot?.isMmol ?: ComplicationRenderer.isMmol()
            val snapshot = data?.snapshot?.copy(nowMs = now) ?: FaceSnapshot(now, isMmol, true, graphHours, null,
                emptyList(), emptyList(), 0f, 0f, null, null)
            val labels = data?.labels ?: FaceLabels.from(context)
            val model = FaceModel.build(snapshot, zonedDateTime.zone, labels)
            lastLayout = painter.draw(
                canvas, bounds.width(), bounds.height(), model, isMmol,
                ambient = renderParameters.drawMode == DrawMode.AMBIENT,
                showData = data != null,
                trial = FaceSources.get() !== ModFaceSource,
                labels = labels,
                traceColor = { value -> guarded { ComplicationRenderer.valueColor(value, isMmol) } ?: TRACE },
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

        /** A kept snapshot this old is read again at the next frame. */
        const val STALE_MS = 50_000L

        /** New readings are read at most this often (history arrives in dozens of pieces while it catches up). */
        const val MIN_READ_MS = 60_000L

        /** The graph's tone where upstream gives no range colour. */
        private const val TRACE = 0xFFE3E2DE.toInt()
    }
}
