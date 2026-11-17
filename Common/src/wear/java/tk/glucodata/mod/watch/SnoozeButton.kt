package tk.glucodata.mod.watch

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * JugglucoNGmod, watch block: what the "Snooze alarm" button does (watch plan, decision 3; user's decision
 * 17.11: an alert going on is snoozed as if "Snooze" was pressed on the phone, no alert - nothing).
 *
 * On a press: the watch's own alert going on is snoozed ([OngoingAlert]; upstream's `/alertack` then silences the
 * phone too) with one short buzz, and `/mod/snooze` always goes to the phone, which snoozes its own alert going on and
 * answers `/mod/snooze/done`. A "snoozed" answer buzzes once, unless the watch buzzed itself within [BUZZ_DEDUP_MS] -
 * so one press is never two buzzes. A repeated press is harmless: upstream's action times drop a second
 * acknowledgement.
 */
object SnoozeButton {

    /** The confirmation buzz, ms. */
    const val BUZZ_MS = 80L
    /** No buzz for the phone's answer when the watch buzzed itself this recently. */
    const val BUZZ_DEDUP_MS = 5_000L

    /** [SystemClock.elapsedRealtime] of the last buzz: a wall clock set back must not change the dedup. */
    @Volatile
    private var lastBuzzElapsedMs = 0L

    /**
     * The press, on the main thread (the button activity's onCreate). [send] gets the application context and the
     * press time ([pressedAtMs], wall clock) and must not block. Returns the kind snoozed on the watch, `null` when no
     * alert was going on here.
     */
    fun press(
        context: Context,
        pressedAtMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        send: (Context, Long) -> Unit = WatchPhoneLink::sendSnooze,
    ): Int? {
        val app = context.applicationContext ?: context
        var kind: Int? = null
        try {
            kind = OngoingAlert.snoozeOngoing()
            if (kind != null) buzz(app, elapsedMs)
            ModWatchLog.i("button: " + if (kind != null) "snoozed kind $kind on the watch" else "no alert on the watch")
        } catch (t: Throwable) {
            ModWatchLog.w("button: local snooze failed: ${t.javaClass.simpleName}")
        }
        try {
            send(app, pressedAtMs)
        } catch (t: Throwable) {
            ModWatchLog.w("button: send failed: ${t.javaClass.simpleName}")
        }
        return kind
    }

    /** The phone's `/mod/snooze/done`. Returns true when it buzzed. */
    fun onPhoneAnswer(
        context: Context,
        done: ModWatchMessages.SnoozeDone,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (done.snoozedKind == null) {
            ModWatchLog.i("phone: no alert")
            return false
        }
        ModWatchLog.i("phone: snoozed kind ${done.snoozedKind}")
        if (!shouldBuzzForPhone(elapsedMs, lastBuzzElapsedMs)) return false
        buzz(context, elapsedMs)
        return true
    }

    /** The phone's "snoozed" buzzes unless the watch buzzed within [BUZZ_DEDUP_MS] (both times - elapsedRealtime). */
    fun shouldBuzzForPhone(elapsedMs: Long, lastBuzzElapsedMs: Long): Boolean =
        lastBuzzElapsedMs <= 0L || elapsedMs < lastBuzzElapsedMs || elapsedMs - lastBuzzElapsedMs >= BUZZ_DEDUP_MS

    internal fun resetForTest() {
        lastBuzzElapsedMs = 0L
    }

    private fun buzz(context: Context, elapsedMs: Long) {
        lastBuzzElapsedMs = elapsedMs
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE), attributes)
        } catch (t: Throwable) {
            ModWatchLog.w("buzz failed: ${t.javaClass.simpleName}")
        }
    }
}
