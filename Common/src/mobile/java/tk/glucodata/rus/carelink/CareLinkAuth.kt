package tk.glucodata.rus.carelink

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tk.glucodata.rus.carelink.CareLinkConfig.long
import tk.glucodata.rus.carelink.CareLinkConfig.str
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** Result of [CareLinkAuth.ensureFresh] / [CareLinkAuth.forceRefresh]. */
sealed class FreshResult {
    /** A live access key (renewed if it was near its end). */
    data class Ok(val session: CareLinkSession) : FreshResult()

    /** Sign-in needed: no session, or the key server refused the refresh key (`invalid_grant`) - the session is wiped. */
    object NeedLogin : FreshResult() {
        override fun toString(): String = "NeedLogin"
    }

    /**
     * Not now: no network, 5xx, 429, an answer that did not parse, a session file that did not open. The session is
     * kept. [code] - HTTP status or 0, [errorClass] - exception class or a short reason (never the body).
     */
    data class Failed(val code: Int, val errorClass: String?) : FreshResult()
}

/** Why a sign-in did not start or did not finish (for the window; no personal data). */
enum class LoginFail { NO_NETWORK, SERVER, BAD_ANSWER, UNSUPPORTED, DENIED, STATE_MISMATCH, NO_PENDING, STORAGE }

/** Start of a sign-in: the Medtronic page to open, or why it cannot open. */
sealed class LoginStart {
    data class Ready(val uri: Uri, val redirectUri: String) : LoginStart()
    data class Failed(val reason: LoginFail, val code: Int) : LoginStart()
}

/**
 * JugglucoNG-RUS, CareLink (stage 4b): sign-in, key renewal and the account role. Moved from Glucove's
 * CareLinkRepository (sign-in, refresh, role, discovery cache); polling and parsing are part 4c.
 *
 * Sign-in: discovery (cached for an hour) -> EU region -> Auth0 settings (any other path - UNSUPPORTED) -> the Medtronic
 * page in [CareLinkLoginActivity] with our `state` mark (18 random bytes); the waiting sign-in (mark and addresses, no
 * secrets) lives in `noBackupFilesDir/rus-carelink/pending.json` and survives the app being unloaded while the user
 * fetches a code from mail -> the return address gives a one-time code -> keys -> region by the country in the key ->
 * role (`users/me`, a care partner also `links/patients`) -> [CareLinkVault] -> [onLoginSuccess].
 *
 * Renewal: when under 10 min are left; request and the rotated key's write run without cancellation (the server may
 * have revoked the old refresh key already). Network, 5xx, 429 - [FreshResult.Failed], the old key is used while it
 * has over a minute left; only 400/401/403 with `invalid_grant` wipes the session ([FreshResult.NeedLogin]).
 *
 * Keys, the user name and cloud answers are never logged (upstream's journal is exported): only codes and classes.
 */
object CareLinkAuth {

    /** Set by RusCareLinkStarter (4c): turns polling on after a sign-in. Called on a background thread. */
    @Volatile
    var onLoginSuccess: ((Context) -> Unit)? = null

    /** Set by RusCareLinkStarter (4c): turns polling off after [signOut]. */
    @Volatile
    var onSignOut: ((Context) -> Unit)? = null

    /** The network; tests put a fake here. */
    @Volatile
    internal var http: CareLinkHttp = CareLinkHttp.system

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Outlives the sign-in window: the code exchange is not cut when the window goes away. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TAG = "RusCareLink"

    /** Renew the key when under 10 min are left. */
    internal const val REFRESH_MARGIN_MS = 10 * 60_000L

    /** Renewal failed for the network - the old key is used while it has more than this left. */
    internal const val MIN_LEFT_MS = 60_000L

    /** A key without an expiry - taken as 3 h (the live key lived 10 775 s). */
    private const val DEFAULT_ACCESS_MS = 3 * 3_600_000L

    private const val DISCOVERY_TTL_MS = 3_600_000L

    /** Refusals after which only a new sign-in helps - these statuses with `invalid_grant` only. */
    private val DEAD_GRANT_STATUSES = setOf(400, 401, 403)

    /** One sign-in or renewal at a time: two renewals with one rotating refresh key would kill the session. */
    private val mutex = Mutex()

    @Volatile
    private var discovery: Pair<Long, JSONObject>? = null

    /** Last session in memory: a renewed key is not lost if its write failed; [cachedUnsaved] - write again. */
    @Volatile
    private var cached: CareLinkSession? = null

    @Volatile
    private var cachedUnsaved = false

    /** Is there a session - by the file only, no decryption (for scheduling; safe on any thread, cheap). */
    fun hasSession(context: Context): Boolean = CareLinkVault.exists(context)

    /** Step 1: region, sign-in settings, the Medtronic page address with our `state` (kept in pending.json). */
    suspend fun beginLogin(context: Context): LoginStart = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = clock()
        val d = discovery(now)
        if (d.first == null) return@withContext LoginStart.Failed(failOf(d.second), d.second.code).also {
            logFail("begin", "discovery", d.second)
        }
        val region = CareLinkConfig.region(d.first!!, null)
            ?: return@withContext LoginStart.Failed(LoginFail.BAD_ANSWER, 0).also { logFail("begin", "no_region", null) }
        if (!region.auth0) {
            logFail("begin", "not_auth0", null)
            return@withContext LoginStart.Failed(LoginFail.UNSUPPORTED, 0)
        }
        val ssoHttp = http.get(region.ssoUrl)
        if (!ssoHttp.ok) {
            logFail("begin", "sso_config", ssoHttp)
            return@withContext LoginStart.Failed(failOf(ssoHttp), ssoHttp.code)
        }
        val sso = CareLinkConfig.parse(ssoHttp.body)?.let(CareLinkConfig::sso)
            ?: return@withContext LoginStart.Failed(LoginFail.BAD_ANSWER, ssoHttp.code).also { logFail("begin", "sso_parse", null) }
        val state = randomState()
        val pending = Pending(state, region.region, sso.tokenUrl, sso.clientId, sso.redirectUri, now)
        try {
            Pending.write(app, pending)
        } catch (t: Throwable) {
            Log.w(TAG, "sign-in not started: pending not written (${t.javaClass.simpleName})")
            return@withContext LoginStart.Failed(LoginFail.STORAGE, 0)
        }
        LoginStart.Ready(Uri.parse(CareLinkConfig.authorizeUrl(sso, state)), sso.redirectUri)
    }

    /** The return address of the waiting sign-in (after the app was unloaded too); null - no sign-in is waiting. */
    fun pendingRedirectUri(context: Context): String? = try {
        Pending.read(context.applicationContext)?.redirectUri
    } catch (_: Throwable) {
        null
    }

    /** Step 2 from the whole return address ([CareLinkConfig.redirect]). null - signed in. */
    suspend fun finishLogin(context: Context, redirectUrl: String): LoginFail? {
        val r = CareLinkConfig.redirect(redirectUrl)
        if (r.error != null || r.code == null) {
            Log.i(TAG, "sign-in: page returned no code")
            return LoginFail.DENIED
        }
        return finishLogin(context, r.code, r.state)
    }

    /**
     * Step 2: mark check -> keys -> role -> session in [CareLinkVault] -> [onLoginSuccess]. null - signed in; otherwise
     * why not. A session that existed before a failed attempt is not touched.
     */
    suspend fun finishLogin(context: Context, code: String, state: String?): LoginFail? = withContext(Dispatchers.IO) {
        mutex.withLock { finishLoginLocked(context.applicationContext, code, state) }
    }

    private fun finishLoginLocked(app: Context, code: String, state: String?): LoginFail? {
        val now = clock()
        val pending = try {
            Pending.read(app)
        } catch (_: Throwable) {
            null
        } ?: return LoginFail.NO_PENDING.also { Log.w(TAG, "sign-in failed: no pending sign-in") }
        if (state != pending.state) return LoginFail.STATE_MISMATCH.also { Log.w(TAG, "sign-in failed: state mismatch") }
        val tokenHttp = http.postForm(pending.tokenUrl, CareLinkConfig.codeForm(pending.clientId, pending.redirectUri, code))
        if (!tokenHttp.ok) {
            logFail("finish", "token_code", tokenHttp)
            return when {
                tokenHttp.code == 0 -> failOf(tokenHttp)
                tokenHttp.code >= 500 || tokenHttp.code == 429 -> LoginFail.SERVER
                else -> LoginFail.DENIED
            }
        }
        val tokens = CareLinkConfig.parse(tokenHttp.body)?.let(CareLinkConfig::tokens)
            ?: return LoginFail.BAD_ANSWER.also { logFail("finish", "token_parse", null) }
        val refresh = tokens.refresh ?: return LoginFail.BAD_ANSWER.also { logFail("finish", "no_refresh_token", null) }
        val claims = CareLinkConfig.claims(tokens.access)
        val username = claims.username ?: return LoginFail.BAD_ANSWER.also { logFail("finish", "no_username", null) }
        // API addresses by the account country; keys come from the sign-in server of the started sign-in.
        val d = discovery(now)
        val disc = d.first ?: return failOf(d.second).also { logFail("finish", "discovery", d.second) }
        val region = CareLinkConfig.region(disc, claims.country) ?: CareLinkConfig.region(disc, null)
            ?: return LoginFail.BAD_ANSWER.also { logFail("finish", "no_region", null) }
        val fresh = CareLinkSession(
            accessToken = tokens.access, refreshToken = refresh, expiresAtMs = expOf(claims, tokens, now),
            username = username, role = null, patientId = null, country = claims.country, region = region.region,
            baseUrlCareLink = region.baseUrlCareLink, baseUrlCumulus = region.baseUrlCumulus,
            tokenUrl = pending.tokenUrl, clientId = pending.clientId, loggedInAtMs = now,
        )
        val roleResult = withRole(fresh)
        val session = roleResult.first
            ?: return failOf(roleResult.second).also { logFail("finish", "users_me", roleResult.second) }
        try {
            CareLinkVault.save(app, session)
        } catch (t: Throwable) {
            Log.w(TAG, "sign-in failed: session not stored (${t.javaClass.simpleName})")
            return LoginFail.STORAGE
        }
        cached = session
        cachedUnsaved = false
        Pending.clear(app)
        Log.i(TAG, "sign-in ok: region=${session.region} role=${session.role}")
        try {
            onLoginSuccess?.invoke(app)
        } catch (t: Throwable) {
            Log.w(TAG, "after sign-in listener failed (${t.javaClass.simpleName})")
        }
        return null
    }

    /** A live access key: renewed when under [REFRESH_MARGIN_MS] is left. Off the main thread (Keystore, network). */
    suspend fun ensureFresh(context: Context): FreshResult = fresh(context.applicationContext, force = false)

    /** Renew now, whatever the expiry (the cloud answered 401/403 to the current key). */
    suspend fun forceRefresh(context: Context): FreshResult = fresh(context.applicationContext, force = true)

    /** Sign out: wipe the session and a waiting sign-in, then [onSignOut] (4c: polling off). */
    fun signOut(context: Context) {
        val app = context.applicationContext
        cached = null
        cachedUnsaved = false
        try {
            CareLinkVault.clear(app)
        } catch (t: Throwable) {
            Log.w(TAG, "sign-out: session file not removed (${t.javaClass.simpleName})")
        }
        try {
            Pending.clear(app)
        } catch (_: Throwable) {
        }
        Log.i(TAG, "signed out")
        try {
            onSignOut?.invoke(app)
        } catch (t: Throwable) {
            Log.w(TAG, "sign-out listener failed (${t.javaClass.simpleName})")
        }
    }

    private suspend fun fresh(app: Context, force: Boolean): FreshResult = withContext(Dispatchers.IO) {
        mutex.withLock { freshLocked(app, force) }
    }

    private suspend fun freshLocked(app: Context, force: Boolean): FreshResult {
        val stored = when (val r = readSession(app)) {
            is SessionRead.Found -> r.session
            SessionRead.None -> return FreshResult.NeedLogin
            is SessionRead.Broken -> return FreshResult.Failed(0, r.errorClass)
        }
        val s = if (!force && stored.expiresAtMs - clock() > REFRESH_MARGIN_MS) {
            stored
        } else {
            when (val r = refresh(app, stored, force)) {
                is FreshResult.Ok -> r.session
                else -> return r
            }
        }
        if (s.role != null) return FreshResult.Ok(s)
        // Role not known yet (sign-in always asks it; kept for safety): ask now.
        val roleResult = withRole(s)
        val withRole = roleResult.first ?: return FreshResult.Failed(roleResult.second.code, roleResult.second.errorClass ?: "no_role")
        saveSession(app, withRole)
        return FreshResult.Ok(withRole)
    }

    private suspend fun refresh(app: Context, s: CareLinkSession, force: Boolean): FreshResult {
        // Request and write without cancellation: the server may already have issued a new refresh key instead of the
        // old one, and a cancel after its answer would lose it - the next poll would come with the revoked old key.
        val (result, next) = withContext(NonCancellable) {
            val result = http.postForm(s.tokenUrl, CareLinkConfig.refreshForm(s.clientId, s.refreshToken))
            val tokens = if (result.ok) CareLinkConfig.parse(result.body)?.let(CareLinkConfig::tokens) else null
            val next = tokens?.let { t ->
                val now = clock()
                s.copy(
                    accessToken = t.access, refreshToken = t.refresh ?: s.refreshToken,
                    expiresAtMs = expOf(CareLinkConfig.claims(t.access), t, now), refreshedAtMs = now,
                ).also { saveSession(app, it) }
            }
            result to next
        }
        if (next != null) {
            Log.i(TAG, "key renewed: force=$force inMin=${(next.expiresAtMs - clock()) / 60_000} rotated=${next.refreshToken != s.refreshToken}")
            return FreshResult.Ok(next)
        }
        if (result.ok) {
            Log.w(TAG, "key renewal answer did not parse")
            return FreshResult.Failed(result.code, "bad_answer")
        }
        // The server refused this very refresh key (expired or revoked: invalid_grant) - only a new sign-in helps.
        // Other refusals (429, 408, 403 without invalid_grant, 5xx) are the cloud's trouble: the session is kept.
        val grantDead = result.code in DEAD_GRANT_STATUSES &&
            CareLinkConfig.tokenError(CareLinkConfig.parse(result.body))?.startsWith("invalid_grant") == true
        if (grantDead) {
            Log.w(TAG, "key renewal refused (${result.code} invalid_grant): sign-in needed, session wiped")
            cached = null
            cachedUnsaved = false
            try {
                CareLinkVault.clear(app)
            } catch (t: Throwable) {
                Log.w(TAG, "session file not removed (${t.javaClass.simpleName})")
            }
            return FreshResult.NeedLogin
        }
        Log.w(TAG, "key renewal failed: code=${result.code} error=${result.errorClass}")
        // No network or a server failure: the old key still lives - use it, renew next time. A forced renewal (the cloud
        // already refused this key) is not answered with the old key.
        if (!force && s.expiresAtMs - clock() > MIN_LEFT_MS) return FreshResult.Ok(s)
        return FreshResult.Failed(result.code, result.errorClass ?: "http_${result.code}")
    }

    /** Role from `users/me`; a care partner also gets the patient name from `links/patients`. */
    private fun withRole(s: CareLinkSession): Pair<CareLinkSession?, HttpResult> {
        val headers = CareLinkHttp.bearer(s.accessToken)
        val me = http.get(s.baseUrlCareLink + "/users/me", headers)
        if (!me.ok) return null to me
        val role = CareLinkConfig.parse(me.body)?.str("role") ?: return null to me
        var next = s.copy(role = role)
        if (next.carePartner) {
            val p = http.get(next.baseUrlCareLink + "/links/patients", headers)
            if (!p.ok) return null to p
            val first = CareLinkConfig.parseArray(p.body)?.optJSONObject(0)
            val patient = first?.str("username") ?: return null to p
            next = next.copy(patientId = patient)
        }
        return next to me
    }

    private sealed class SessionRead {
        data class Found(val session: CareLinkSession) : SessionRead()
        object None : SessionRead()
        data class Broken(val errorClass: String) : SessionRead()
    }

    /**
     * The session: from memory first (one not yet stored is written again), else from the file. No file - sign-in
     * needed; a file that does not open - a storage failure, the file is kept (Keystore may be locked before unlock).
     */
    private fun readSession(app: Context): SessionRead {
        cached?.let { c ->
            if (!CareLinkVault.exists(app) && !cachedUnsaved) {
                // the file is gone (app data cleared): memory must not bring the session back
                cached = null
                return SessionRead.None
            }
            if (cachedUnsaved) writeVault(app, c)
            return SessionRead.Found(c)
        }
        val s = try {
            CareLinkVault.load(app)
        } catch (t: Throwable) {
            Log.w(TAG, "session not readable (${(t.cause ?: t).javaClass.simpleName})")
            return SessionRead.Broken((t.cause ?: t).javaClass.simpleName)
        } ?: return SessionRead.None
        cached = s
        cachedUnsaved = false
        return SessionRead.Found(s)
    }

    /** Session to memory at once, to the file as an attempt; a failed write is marked and repeated next time. */
    private fun saveSession(app: Context, s: CareLinkSession) {
        cached = s
        writeVault(app, s)
    }

    private fun writeVault(app: Context, s: CareLinkSession): Boolean = try {
        CareLinkVault.save(app, s)
        cachedUnsaved = false
        true
    } catch (t: Throwable) {
        cachedUnsaved = true
        Log.w(TAG, "renewed key not stored, kept in memory (${t.javaClass.simpleName})")
        false
    }

    /** Discovery, one request an hour. first = the answer, second = the last HTTP result (for the error code). */
    private fun discovery(nowMs: Long): Pair<JSONObject?, HttpResult> {
        discovery?.let { (at, d) -> if (nowMs - at in 0 until DISCOVERY_TTL_MS) return d to HttpResult(200, null, null) }
        val r = http.get(CareLinkConfig.DISCOVERY_URL)
        val d = if (r.ok) CareLinkConfig.parse(r.body) else null
        if (d != null) discovery = nowMs to d
        return d to r
    }

    private fun failOf(r: HttpResult): LoginFail = when {
        r.code == 0 && r.error != null -> LoginFail.NO_NETWORK
        r.ok -> LoginFail.BAD_ANSWER
        else -> LoginFail.SERVER
    }

    private fun logFail(step: String, why: String, r: HttpResult?) {
        Log.w(TAG, "sign-in $step failed: $why code=${r?.code} error=${r?.errorClass}")
    }

    private fun expOf(claims: CareLinkConfig.Claims, tokens: CareLinkConfig.Tokens, nowMs: Long): Long =
        claims.expMs ?: tokens.expiresInSec?.let { nowMs + it * 1000 } ?: (nowMs + DEFAULT_ACCESS_MS)

    private fun randomState(): String {
        val b = ByteArray(18)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Forget memory state (tests; a fresh process has none). */
    internal fun resetForTest() {
        cached = null
        cachedUnsaved = false
        discovery = null
        onLoginSuccess = null
        onSignOut = null
        http = CareLinkHttp.system
        clock = { System.currentTimeMillis() }
    }

    /**
     * A sign-in waiting for the return from the Medtronic page: our mark and the addresses of the started sign-in, no
     * secrets. File `noBackupFilesDir/rus-carelink/pending.json`, not SharedPreferences: upstream's backup takes every
     * shared_prefs file.
     */
    internal data class Pending(
        val state: String,
        val region: String,
        val tokenUrl: String,
        val clientId: String,
        val redirectUri: String,
        val startedAtMs: Long,
    ) {
        companion object {
            private fun file(app: Context) = File(CareLinkVault.dir(app), "pending.json")

            fun write(app: Context, p: Pending) {
                val o = JSONObject()
                    .put("state", p.state).put("region", p.region).put("tokenUrl", p.tokenUrl)
                    .put("clientId", p.clientId).put("redirectUri", p.redirectUri).put("startedAtMs", p.startedAtMs)
                CareLinkVault.writeAtomically(file(app), o.toString().toByteArray(Charsets.UTF_8))
            }

            fun read(app: Context): Pending? {
                val f = file(app)
                if (!f.exists()) return null
                val o = CareLinkConfig.parse(f.readText(Charsets.UTF_8)) ?: return null
                return Pending(
                    state = o.str("state") ?: return null,
                    region = o.str("region") ?: return null,
                    tokenUrl = o.str("tokenUrl") ?: return null,
                    clientId = o.str("clientId") ?: return null,
                    redirectUri = o.str("redirectUri") ?: return null,
                    startedAtMs = o.long("startedAtMs") ?: 0L,
                )
            }

            fun clear(app: Context) {
                val f = file(app)
                f.delete()
                File(f.parentFile, f.name + ".tmp").delete()
            }
        }
    }
}
