package tk.glucodata.rus.carelink.trial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * JugglucoNG-RUS, CareLink trial entry point (part 4p). This file lives in Common/src/rusTrial/java, which
 * rus/rus-init.gradle adds to the phone `rus` build only with -PrusRelease=0 (test builds, emulator only): a candidate
 * for the phone does not contain it, and rus/check-apk.py checks that by the marker strings below.
 *
 * [RusCareLinkStarter] finds this class by name on every process start; [install] puts the fake network in place of
 * CareLink's (from the start of the process: a poll of the alarm or WorkManager never reaches the real cloud) and
 * registers the command receiver - exported, but only a sender holding android.permission.DUMP (adb shell) gets in:
 *   adb shell am broadcast -a tk.glucodata.ng.rus.CARELINK_TRIAL -p tk.glucodata.ng.rus --es cmd poll
 * Each result is a line of the log (tag below) and the broadcast's result data (printed by `am broadcast`).
 * android.util.Log.println, not Log.i: upstream's R8 rules (Common/proguard-rules.log) strip Log.i/w/d/v in release
 * builds, `rus` included.
 */
object CareLinkTrialEntry {

    private const val ACTION = "tk.glucodata.ng.rus.CARELINK_TRIAL"
    private const val TAG = "RusCareLinkTrial"
    private const val SENDER_PERMISSION = "android.permission.DUMP"

    /** A broadcast receiver may hold its result this long; a longer command goes on and logs its result later. */
    private const val ANSWER_WITHIN_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var installed = false

    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        CareLinkTrial.installHttp()
        ContextCompat.registerReceiver(app, Receiver(), IntentFilter(ACTION), SENDER_PERMISSION, null,
            ContextCompat.RECEIVER_EXPORTED)
        Log.println(Log.INFO, TAG, "trial installed: CareLink network is the made-up pump, commands by am broadcast")
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")
            val arg = intent.getStringExtra("arg")
            val app = context.applicationContext
            val pending = goAsync()
            scope.launch {
                val work = scope.async {
                    try {
                        CareLinkTrial.run(app, cmd, arg)
                    } catch (t: Throwable) {
                        "error: ${t.javaClass.simpleName}"
                    }
                }
                val result = try {
                    withTimeout(ANSWER_WITHIN_MS) { work.await() }
                } catch (_: TimeoutCancellationException) {
                    scope.launch { Log.println(Log.INFO, TAG, "$cmd (late): ${work.await()}") }
                    "$cmd: still running, result in the log"
                }
                Log.println(Log.INFO, TAG, result)
                try {
                    pending.resultData = result
                } catch (_: Throwable) {
                    // not an ordered broadcast: the log line is the result
                }
                pending.finish()
            }
        }
    }
}
