package tk.glucodata.rus.carelink

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

/** Why a poll runs: the alarm chain, the 15-min safety work, right after a sign-in, the app opened, a button. */
enum class PollReason { ALARM, WORKER, LOGIN, FOREGROUND, MANUAL }

/**
 * JugglucoNG-RUS, CareLink (stage 4c): one poll of the cloud. Moved from Glucove's CareLinkRepository.poll, without
 * its journal, basal watch and modes.
 *
 * One poll at a time ([Mutex]). Alarm and safety work poll only when due (interval of the settings, a minute of
 * slack); the app opening - not more often than every 2 min; sign-in and the button - always. Then: a live key
 * ([CareLinkAuth.ensureFresh]) -> POST `baseUrlCumulus/display/message` (`{"username", "role": "patient"}`, a care
 * partner `carepartner` + `patientId`) -> on 401/403 [CareLinkAuth.forceRefresh] and one more try -> parsing ->
 * [CareLinkJournalWriter], [CareLinkBasalWriter], [CareLinkIob] -> state.
 *
 * `NeedLogin` (only `invalid_grant`) -> NEED_LOGIN, alarms off, notification. Network, 5xx, a broken answer, a failed
 * parse or write -> ERROR: nothing is wiped, the last snapshot stays and `lastDataAtMs` does not move. The empty answer
 * (no pump upload yet) is a good poll that brings no data: the data time stays where it was.
 *
 * Off the main thread (Keystore, network). Keys, the user name and the answer are never logged.
 */
object CareLinkPoller {

    private const val TAG = "RusCareLink"

    /** The app opening polls not more often than this. */
    internal const val FOREGROUND_GAP_MS = 2 * 60_000L

    /** An alarm a little early still polls (the previous poll ran a few seconds after its own alarm). */
    internal const val DUE_SLACK_MS = 60_000L

    private val mutex = Mutex()

    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    // Stage 4d fills the writers; tests put failing ones here.
    @Volatile
    internal var writeJournal: suspend (List<ClEvent>, Boolean) -> Unit = { e, first -> CareLinkJournalWriter.apply(e, first) }

    @Volatile
    internal var writeBasal: suspend (PumpNow?, Long) -> Unit = { p, at -> CareLinkBasalWriter.apply(p, at) }

    @Volatile
    internal var publishIob: (PumpNow?) -> Unit = { p -> CareLinkIob.publish(p) }

    /**
     * One poll; the state after it (unchanged when skipped: off, no session, not due). [firstPoll] - the first poll
     * after a sign-in (the journal writer takes the whole 24 h); also true while no pump data was ever written.
     */
    suspend fun poll(context: Context, reason: PollReason, firstPoll: Boolean = false): CareLinkState =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            mutex.withLock { pollLocked(app, reason, firstPoll) }
        }

    private suspend fun pollLocked(app: Context, reason: PollReason, firstPollAsked: Boolean): CareLinkState {
        val store = CareLinkStateStore(app)
        val settings = CareLinkSettings(app)
        val before = store.read()
        if (!settings.enabled || !CareLinkAuth.hasSession(app)) return before
        val now = CareLinkAuth.clock()
        val last = before.lastPollAtMs
        when (reason) {
            PollReason.ALARM, PollReason.WORKER -> {
                val interval = settings.intervalMin * 60_000L
                if (last != null && now - last in 0 until interval - DUE_SLACK_MS) return before
            }
            PollReason.FOREGROUND -> if (last != null && now - last in 0 until FOREGROUND_GAP_MS) return before
            PollReason.LOGIN, PollReason.MANUAL -> {}
        }
        val firstPoll = firstPollAsked || reason == PollReason.LOGIN || before.lastDataAtMs == null

        var session = when (val f = CareLinkAuth.ensureFresh(app)) {
            is FreshResult.Ok -> f.session
            FreshResult.NeedLogin -> return needLogin(app, store, now, reason)
            is FreshResult.Failed -> return failed(app, store, now, reason, keyError(f))
        }
        var http = displayMessage(session)
        if (http.auth) {
            // the access key was revoked before its time: renew and try once more
            session = when (val f = CareLinkAuth.forceRefresh(app)) {
                is FreshResult.Ok -> f.session
                FreshResult.NeedLogin -> return needLogin(app, store, now, reason)
                is FreshResult.Failed -> return failed(app, store, now, reason, keyError(f))
            }
            http = displayMessage(session)
            // renewed and refused again: the cloud's trouble, not the end of the session - keys are kept
        }
        if (!http.ok) return failed(app, store, now, reason, httpError(http))
        val root = CareLinkConfig.parse(http.body)
        if (root == null || !CareLinkData.looksValid(root)) {
            return failed(app, store, now, reason, CareLinkError(CareLinkErrorKind.BAD_ANSWER, http.code, "not_pump_data"))
        }

        val events: List<ClEvent>
        val pump: PumpNow?
        val dataAt: Long?
        try {
            events = CareLinkMarkers.parse(root, zone())
            pump = CareLinkPumpNow.parse(root, now, zone())
            // the empty answer brings no data time: it would move it back (Glucove 03.11, 38 min)
            dataAt = if (pump == null) null else CareLinkData.pumpDataAt(root)?.let { minOf(it, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed(app, store, now, reason, CareLinkError(CareLinkErrorKind.PARSE, 0, t.javaClass.simpleName))
        }

        try {
            writeJournal(events, firstPoll)
            writeBasal(pump, now)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed(app, store, now, reason, CareLinkError(CareLinkErrorKind.WRITE, 0, t.javaClass.simpleName))
        }
        try {
            publishIob(pump)
        } catch (t: Throwable) {
            // the IoB plate is a display: a failure here does not undo the written poll
            Log.w(TAG, "poll: active insulin not published (${t.javaClass.simpleName})")
        }

        // signed out while this poll ran: its result must not bring the state back
        if (!CareLinkSettings(app).enabled) return store.read()
        val after = store.update { cur ->
            cur.copy(
                status = CareLinkStatus.OK,
                lastPollAtMs = now,
                lastDataAtMs = listOfNotNull(cur.lastDataAtMs, dataAt).maxOrNull(),
                lastError = null,
                pumpNow = CareLinkPumpNow.merge(cur.pumpNow, pump),
            )
        }
        Log.i(TAG, "poll ok: reason=$reason first=$firstPoll events=${events.size} empty=${pump == null} code=${http.code}")
        CareLinkNotifier.afterPoll(app, after, now)
        return after
    }

    private fun displayMessage(s: CareLinkSession): HttpResult {
        val body = JSONObject().put("username", s.username)
        if (s.carePartner) {
            body.put("role", "carepartner")
            s.patientId?.let { body.put("patientId", it) }
        } else {
            body.put("role", "patient")
        }
        return CareLinkAuth.http.postJson(
            s.baseUrlCumulus.trimEnd('/') + "/display/message", body.toString(), CareLinkHttp.bearer(s.accessToken),
        )
    }

    private fun needLogin(app: Context, store: CareLinkStateStore, now: Long, reason: PollReason): CareLinkState {
        // a sign-out during this poll also ends the session: no "sign in again" for a deliberate sign-out
        if (!CareLinkSettings(app).enabled) return store.read()
        val after = store.update { it.copy(status = CareLinkStatus.NEED_LOGIN, lastPollAtMs = now, lastError = null) }
        Log.w(TAG, "poll: sign-in needed (reason=$reason)")
        try {
            CareLinkScheduler.disarm(app)
        } catch (t: Throwable) {
            Log.w(TAG, "alarms not removed (${t.javaClass.simpleName})")
        }
        CareLinkNotifier.needLogin(app)
        return after
    }

    private fun failed(app: Context, store: CareLinkStateStore, now: Long, reason: PollReason, e: CareLinkError): CareLinkState {
        if (!CareLinkSettings(app).enabled) return store.read()
        val after = store.update { it.copy(status = CareLinkStatus.ERROR, lastPollAtMs = now, lastError = e) }
        Log.w(TAG, "poll failed: reason=$reason kind=${e.kind} code=${e.code} error=${e.errorClass}")
        CareLinkNotifier.afterPoll(app, after, now)
        return after
    }

    /**
     * A renewal that did not give a key. Code 0 means no HTTP answer: a network exception class - no network; the
     * renewal answer did not parse - a bad answer; anything else (Keystore locked before unlock, a broken session
     * file: [CareLinkAuth] gives the exception class) - storage.
     */
    private fun keyError(f: FreshResult.Failed): CareLinkError = when {
        f.code == 0 && f.errorClass in NETWORK_ERRORS -> CareLinkError(CareLinkErrorKind.NO_NETWORK, 0, f.errorClass)
        f.code == 0 && f.errorClass == "bad_answer" -> CareLinkError(CareLinkErrorKind.BAD_ANSWER, 0, f.errorClass)
        f.code == 0 -> CareLinkError(CareLinkErrorKind.STORAGE, 0, f.errorClass)
        f.code in 200..299 -> CareLinkError(CareLinkErrorKind.BAD_ANSWER, f.code, f.errorClass)
        else -> CareLinkError(CareLinkErrorKind.SERVER, f.code, f.errorClass)
    }

    /** Exception classes of [UrlConnectionCareLinkHttp] with no answer: no network, a cut or slow connection, TLS. */
    private val NETWORK_ERRORS = setOf(
        "UnknownHostException", "ConnectException", "NoRouteToHostException", "SocketException", "SocketTimeoutException",
        "InterruptedIOException", "IOException", "EOFException", "ProtocolException", "SSLException",
        "SSLHandshakeException", "SSLPeerUnverifiedException", "SSLProtocolException", "ConnectTimeoutException",
    )

    private fun httpError(r: HttpResult): CareLinkError = when {
        r.code == 0 -> CareLinkError(CareLinkErrorKind.NO_NETWORK, 0, r.errorClass)
        else -> CareLinkError(CareLinkErrorKind.SERVER, r.code, r.errorClass)
    }

    /** Back to defaults (tests). */
    internal fun resetForTest() {
        zone = { ZoneId.systemDefault() }
        writeJournal = { e, first -> CareLinkJournalWriter.apply(e, first) }
        writeBasal = { p, at -> CareLinkBasalWriter.apply(p, at) }
        publishIob = { p -> CareLinkIob.publish(p) }
    }
}
