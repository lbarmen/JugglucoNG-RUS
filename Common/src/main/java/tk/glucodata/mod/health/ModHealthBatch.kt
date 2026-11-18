package tk.glucodata.mod.health

import kotlin.math.abs
import tk.glucodata.mod.watch.ModWatchMessages

/**
 * JugglucoNGmod, watch block (part 4): one heart rate and steps batch, the Data Layer item the watch puts at
 * `/mod/health/batch/<seq>` (DataClient) and the phone reads (part 5). One plain class for both sides (src/main),
 * tested once for both test tasks; no Google Play services classes here - the DataMap adapter is [ModHealthDataMap].
 *
 * A batch is four arrays of one length - kind, time (ms of the epoch, watch clock), value, extra - plus the format
 * version ([VERSION]), the batch number [seq] (grows from 1 and survives a restart of the watch process) and the watch
 * time it was sent. The watch puts only the kinds in [KINDS] (5-minute heart rate means, the day's step count, the
 * watch battery and the status line): Data Layer items may travel through Google's servers when the phone is far
 * away (watch plan, "Risks"). A batch stays under [MAX_BYTES] (a Data Layer item holds 100 KB); [chunks] cuts a queue
 * into such pieces.
 */
data class ModHealthBatch(
    val seq: Long,
    val sentAtMs: Long,
    val samples: List<Sample>,
    val version: Int = VERSION,
) {

    /**
     * One row of a batch. [KIND_HR5]: [timeMs] - start of the 5-minute window (aligned to the clock), [value] - mean
     * beats per minute, [extra] - `n=<count>;min=<bpm>;max=<bpm>`. [KIND_STEPS_DAY]: [timeMs] - end of the count,
     * [value] - steps since the start of the watch's day, [extra] - that start (ms of the epoch). [KIND_BATTERY]:
     * [value] - watch battery percent, [extra] - [CHARGING] or empty. [KIND_STATUS]: [value] 0, [extra] -
     * `key=value;...` (app version, sdk, permissions, subscription, ages of the last delivery; never heart rate or
     * steps).
     */
    data class Sample(val kind: String, val timeMs: Long, val value: Float, val extra: String = "") {
        /** About how many bytes this row takes in a batch (the same estimate Glucove cut its batches by). */
        fun approxBytes(): Int = 16 + kind.length + extra.length * 2
    }

    /** What reading an item gave: a batch, an item of a newer watch app (leave it for a newer phone app), or junk. */
    sealed interface Decoded {
        data class Ok(val batch: ModHealthBatch) : Decoded
        data class Newer(val version: Int) : Decoded
        data class Broken(val reason: String) : Decoded
    }

    /** The Data Layer path of this batch. */
    val path: String get() = path(seq)

    fun kinds(): Array<String> = samples.map { it.kind }.toTypedArray()
    fun times(): LongArray = samples.map { it.timeMs }.toLongArray()
    fun values(): FloatArray = samples.map { it.value }.toFloatArray()
    fun extras(): Array<String> = samples.map { it.extra }.toTypedArray()

    /** The rows of the kinds this code knows ([KINDS]); a newer watch of the same version may add others. */
    fun knownSamples(): List<Sample> = samples.filter { it.kind in KINDS }

    companion object {
        const val VERSION = 1

        const val KEY_VERSION = "v"
        const val KEY_SEQ = "seq"
        const val KEY_SENT_AT = "sentAt"
        const val KEY_KIND = "kind"
        const val KEY_TIME = "time"
        const val KEY_VALUE = "value"
        const val KEY_EXTRA = "extra"

        const val KIND_HR5 = "hr5"
        const val KIND_STEPS_DAY = "steps_day"
        const val KIND_BATTERY = "battery"
        const val KIND_STATUS = "status"

        /** The only kinds a batch carries. */
        val KINDS: Set<String> = setOf(KIND_HR5, KIND_STEPS_DAY, KIND_BATTERY, KIND_STATUS)

        /** Extra of a [KIND_BATTERY] row when the watch is on its charger. */
        const val CHARGING = "charging"

        /** Most bytes of rows in one batch (a Data Layer item holds 100 KB; half of it leaves room for the keys). */
        const val MAX_BYTES = 50_000

        /** A day-start moving by more than this is a new day; less is the watch's own jitter of milliseconds. */
        const val NEW_DAY_SHIFT_MS = 60 * 60_000L

        fun path(seq: Long): String = ModWatchMessages.PATH_HEALTH_BATCH_PREFIX + seq

        /** The batch number in a [path], `null` when the path is not a batch path or its number is broken. */
        fun seqFromPath(path: String?): Long? {
            if (path == null || !path.startsWith(ModWatchMessages.PATH_HEALTH_BATCH_PREFIX)) return null
            return path.substring(ModWatchMessages.PATH_HEALTH_BATCH_PREFIX.length).toLongOrNull()?.takeIf { it >= 1 }
        }

        /** A batch to send: the rows of [samples] of the known [KINDS] only. */
        fun forSending(seq: Long, sentAtMs: Long, samples: List<Sample>): ModHealthBatch =
            ModHealthBatch(seq, sentAtMs, samples.filter { it.kind in KINDS })

        /**
         * A batch from its fields as an item holds them. A version above [VERSION] is [Decoded.Newer]; version 0 (a
         * missing key), a missing array, arrays of different lengths, a null kind or a broken header are
         * [Decoded.Broken] - never an exception.
         */
        fun decode(
            version: Int,
            seq: Long,
            sentAtMs: Long,
            kinds: Array<out String?>?,
            times: LongArray?,
            values: FloatArray?,
            extras: Array<out String?>?,
        ): Decoded {
            if (version > VERSION) return Decoded.Newer(version)
            if (version < 1) return Decoded.Broken("version")
            if (kinds == null || times == null || values == null || extras == null) return Decoded.Broken("missing")
            val n = kinds.size
            if (times.size != n || values.size != n || extras.size != n) return Decoded.Broken("lengths")
            if (seq < 1 || sentAtMs < 0) return Decoded.Broken("header")
            val samples = ArrayList<Sample>(n)
            for (i in 0 until n) {
                val kind = kinds[i] ?: return Decoded.Broken("kind")
                samples += Sample(kind, times[i], values[i], extras[i].orEmpty())
            }
            return Decoded.Ok(ModHealthBatch(seq, sentAtMs, samples, version))
        }

        /**
         * Cuts [samples] into batches of at most [maxBytes] ([Sample.approxBytes]), in order; a single row larger
         * than that goes alone instead of looping.
         */
        fun chunks(samples: List<Sample>, maxBytes: Int = MAX_BYTES): List<List<Sample>> {
            val out = mutableListOf<List<Sample>>()
            var current = mutableListOf<Sample>()
            var bytes = 0
            for (s in samples) {
                val size = s.approxBytes()
                if (current.isNotEmpty() && bytes + size > maxBytes) {
                    out += current
                    current = mutableListOf()
                    bytes = 0
                }
                current += s
                bytes += size
            }
            if (current.isNotEmpty()) out += current
            return out
        }

        /**
         * Two [KIND_STEPS_DAY] rows with day starts [previousStartMs] and [startMs] belong to different days: the start
         * moved by more than [NEW_DAY_SHIFT_MS] (the phone counts differences of totals within one day, part 5).
         */
        fun isNewDay(previousStartMs: Long, startMs: Long): Boolean = abs(startMs - previousStartMs) > NEW_DAY_SHIFT_MS
    }
}
