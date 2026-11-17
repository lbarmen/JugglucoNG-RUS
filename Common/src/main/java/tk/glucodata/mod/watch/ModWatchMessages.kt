package tk.glucodata.mod.watch

import java.nio.ByteBuffer

/**
 * JugglucoNGmod, watch block: the Data Layer contract between our phone and watch code - paths, capabilities and the
 * byte format of our messages. One class for both sides (src/main), tested once for both test tasks.
 *
 * Paths start with [PATH_PREFIX]: upstream's MessageReceiver filters list their own prefixes only, so it never gets
 * ours; our listeners (watch: ModWearListener, phone: ModPhoneListener) filter on `/mod/`.
 *
 * Every message is one version byte ([VERSION]) and then the fields of that path, big-endian. A reader takes the
 * fields it knows and ignores a longer tail, so a later part can append fields without breaking an older peer; a
 * message that is empty, too short, of version 0 or of a newer version is `null` - never an exception.
 */
object ModWatchMessages {

    const val VERSION: Int = 1

    const val PATH_PREFIX = "/mod/"
    /** Watch -> phone: the snooze button was pressed. Body: press time (ms, long). */
    const val PATH_SNOOZE = "/mod/snooze"
    /** Phone -> watch: answer to [PATH_SNOOZE]. Body: snoozed kind (int, [NO_ALERT] = none) and phone time (ms, long). */
    const val PATH_SNOOZE_DONE = "/mod/snooze/done"
    /** Phone -> watch, every 5 minutes: wake-up and phone battery (body - part 2). */
    const val PATH_TICK = "/mod/tick"
    /** Watch -> phone (DataClient): heart rate and steps batches, `/mod/health/batch/<number>` (part 4). */
    const val PATH_HEALTH_BATCH_PREFIX = "/mod/health/batch/"

    /** Capability the phone app declares (upstream's mobile/res/values/wear.xml); the watch looks for it. */
    const val CAPABILITY_PHONE = "juggluco"
    /** Capability the watch app declares (upstream's wear/res/values/wear.xml); the phone looks for it. */
    const val CAPABILITY_WATCH = "jugglucowatch"

    /** Kind in a [SnoozeDone] when no standard alert was going on. */
    const val NO_ALERT = -1

    data class SnoozeRequest(val pressedAtMs: Long)

    /** [snoozedKind] - the alert kind that was snoozed (AlertType id), `null` when none was going on. */
    data class SnoozeDone(val snoozedKind: Int?, val atMs: Long)

    fun encodeSnooze(request: SnoozeRequest): ByteArray =
        allocate(Long.SIZE_BYTES).putLong(request.pressedAtMs).array()

    fun decodeSnooze(data: ByteArray?): SnoozeRequest? {
        val body = open(data, Long.SIZE_BYTES) ?: return null
        val at = body.long
        return if (at < 0) null else SnoozeRequest(at)
    }

    fun encodeSnoozeDone(done: SnoozeDone): ByteArray =
        allocate(Int.SIZE_BYTES + Long.SIZE_BYTES)
            .putInt(done.snoozedKind ?: NO_ALERT)
            .putLong(done.atMs)
            .array()

    fun decodeSnoozeDone(data: ByteArray?): SnoozeDone? {
        val body = open(data, Int.SIZE_BYTES + Long.SIZE_BYTES) ?: return null
        val kind = body.int
        val at = body.long
        if (kind < NO_ALERT || at < 0) return null
        return SnoozeDone(if (kind == NO_ALERT) null else kind, at)
    }

    /** A message buffer: the version byte written, room for [bodyBytes] of fields. */
    fun allocate(bodyBytes: Int): ByteBuffer =
        ByteBuffer.allocate(1 + bodyBytes).put(VERSION.toByte())

    /**
     * The fields of [data], positioned after the version byte, when it holds at least [bodyBytes] of them and a
     * version this code knows; `null` otherwise. Bytes after the known fields are left for the caller to ignore.
     */
    fun open(data: ByteArray?, bodyBytes: Int): ByteBuffer? {
        if (data == null || data.size < 1 + bodyBytes) return null
        val version = data[0].toInt() and 0xff
        if (version < 1 || version > VERSION) return null
        return ByteBuffer.wrap(data, 1, data.size - 1)
    }
}
