package rus.carelink

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import rus.carelink.CareLinkTestData.CARELINK_BASE
import rus.carelink.CareLinkTestData.NO_NETWORK
import rus.carelink.CareLinkTestData.SSO_URL
import rus.carelink.CareLinkTestData.TOKEN_URL
import rus.carelink.CareLinkTestData.access
import rus.carelink.CareLinkTestData.ok
import rus.carelink.CareLinkTestData.status
import tk.glucodata.rus.carelink.AesGcmSessionCipher
import tk.glucodata.rus.carelink.CareLinkAuth
import tk.glucodata.rus.carelink.CareLinkConfig
import tk.glucodata.rus.carelink.CareLinkSession
import tk.glucodata.rus.carelink.CareLinkVault
import tk.glucodata.rus.carelink.FreshResult
import tk.glucodata.rus.carelink.LoginFail
import tk.glucodata.rus.carelink.LoginStart
import java.io.File
import javax.crypto.KeyGenerator

/**
 * JugglucoNG-RUS, CareLink 4b: sign-in, key renewal and sign-out against a fake network (made-up answers only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkAuthTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val net = FakeCareLinkHttp()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pendingFile get() = File(app.noBackupFilesDir, "rus-carelink/pending.json")

    private fun session(expiresInMs: Long, refresh: String = "made-up-refresh-1", role: String? = "PATIENT") = CareLinkSession(
        accessToken = access((now + expiresInMs) / 1000), refreshToken = refresh, expiresAtMs = now + expiresInMs,
        username = "tester", role = role, patientId = null, country = "DE", region = "EU",
        baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = "https://cumulus.example.eu/connect/carepartner/v13",
        tokenUrl = TOKEN_URL, clientId = "abc123", loggedInAtMs = now - 3_600_000L,
    )

    /** A refresh answer: new access key for 3 h, rotated refresh key [refresh] (null - not rotated). */
    private fun renewed(refresh: String?): String {
        val r = if (refresh == null) "" else """, "refresh_token": "$refresh""""
        return """{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800$r}"""
    }

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkAuth.http = net
        CareLinkAuth.clock = { now }
        CareLinkVault.cipher = AesGcmSessionCipher { key }
        CareLinkVault.clear(app)
        pendingFile.delete()
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        pendingFile.delete()
        CareLinkAuth.resetForTest()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    // ---------- renewal ----------

    @Test
    fun noSessionIsNeedLogin() = runBlocking {
        assertFalse(CareLinkAuth.hasSession(app))
        assertEquals(FreshResult.NeedLogin, CareLinkAuth.ensureFresh(app))
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun keyWithTimeLeftIsNotRenewed() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 2 * 3_600_000L))
        assertTrue(CareLinkAuth.hasSession(app))
        val r = CareLinkAuth.ensureFresh(app)
        assertTrue(r is FreshResult.Ok)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun keyNearItsEndIsRenewedAndTheRotatedKeyIsStored() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 9 * 60_000L))
        net.on(TOKEN_URL, ok(renewed("made-up-refresh-2")))
        val r = CareLinkAuth.ensureFresh(app) as FreshResult.Ok
        assertEquals(listOf("FORM $TOKEN_URL refresh_token"), net.calls)
        assertEquals("made-up-refresh-1", net.forms.single()["refresh_token"])
        assertEquals("made-up-refresh-2", r.session.refreshToken)
        assertEquals(now + 10_800_000L, r.session.expiresAtMs)
        assertEquals(now, r.session.refreshedAtMs)
        // on disk too: a new process starts with the rotated key
        assertEquals("made-up-refresh-2", CareLinkVault.load(app)!!.refreshToken)
        CareLinkAuth.resetForTest()
        CareLinkAuth.http = net
        CareLinkAuth.clock = { now }
        assertEquals("made-up-refresh-2", (CareLinkAuth.ensureFresh(app) as FreshResult.Ok).session.refreshToken)
    }

    @Test
    fun refreshKeyNotRotatedKeepsTheOldOne() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 60_000L))
        net.on(TOKEN_URL, ok(renewed(null)))
        val r = CareLinkAuth.ensureFresh(app) as FreshResult.Ok
        assertEquals("made-up-refresh-1", r.session.refreshToken)
        assertEquals(now + 10_800_000L, CareLinkVault.load(app)!!.expiresAtMs)
    }

    @Test
    fun invalidGrantWipesTheSessionAndAsksToSignIn() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            CareLinkVault.save(app, session(expiresInMs = 5 * 60_000L))
            net.on(TOKEN_URL, status(code, """{"error": "invalid_grant", "error_description": "Unknown or invalid refresh token."}"""))
            assertEquals("status $code", FreshResult.NeedLogin, CareLinkAuth.ensureFresh(app))
            assertFalse(CareLinkAuth.hasSession(app))
            assertEquals(FreshResult.NeedLogin, CareLinkAuth.ensureFresh(app))
        }
    }

    @Test
    fun serverErrorOrNoNetworkFailsAndKeepsTheSession() = runBlocking {
        for (answer in listOf(status(500), status(429), NO_NETWORK, status(403, """{"error": "access_denied"}"""))) {
            CareLinkAuth.resetForTest()
            CareLinkAuth.http = net
            CareLinkAuth.clock = { now }
            // under a minute left: the old key cannot be used
            CareLinkVault.save(app, session(expiresInMs = 30_000L))
            net.on(TOKEN_URL, answer)
            val r = CareLinkAuth.ensureFresh(app)
            assertTrue("$answer -> $r", r is FreshResult.Failed)
            assertEquals(answer.code, (r as FreshResult.Failed).code)
            assertNotNull(r.errorClass)
            assertTrue(CareLinkAuth.hasSession(app))
            assertEquals("made-up-refresh-1", CareLinkVault.load(app)!!.refreshToken)
        }
    }

    @Test
    fun failedRenewalUsesTheOldKeyWhileItLivesOverAMinute() = runBlocking {
        val s = session(expiresInMs = 5 * 60_000L)
        CareLinkVault.save(app, s)
        net.on(TOKEN_URL, NO_NETWORK)
        val r = CareLinkAuth.ensureFresh(app) as FreshResult.Ok
        assertEquals(s.accessToken, r.session.accessToken)
        assertEquals(1, net.calls.size)
        // forced renewal (the cloud refused this key) never falls back to the old key
        val forced = CareLinkAuth.forceRefresh(app)
        assertTrue(forced is FreshResult.Failed)
        assertTrue(CareLinkAuth.hasSession(app))
    }

    @Test
    fun forceRefreshRenewsAKeyWithTimeLeft() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 2 * 3_600_000L))
        net.on(TOKEN_URL, ok(renewed("made-up-refresh-2")))
        val r = CareLinkAuth.forceRefresh(app) as FreshResult.Ok
        assertEquals("made-up-refresh-2", r.session.refreshToken)
    }

    @Test
    fun answerThatDoesNotParseFailsAndKeepsTheSession() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 5 * 60_000L))
        net.on(TOKEN_URL, ok("""{"unexpected": true}"""))
        val r = CareLinkAuth.ensureFresh(app)
        assertEquals(FreshResult.Failed(200, "bad_answer"), r)
        assertTrue(CareLinkAuth.hasSession(app))
    }

    @Test
    fun unreadableSessionFileIsAStorageFailureNotASignOut() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 2 * 3_600_000L))
        CareLinkVault.cipher = AesGcmSessionCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val r = CareLinkAuth.ensureFresh(app)
        assertTrue(r is FreshResult.Failed)
        assertTrue(CareLinkAuth.hasSession(app))
    }

    @Test
    fun sessionWithoutRoleAsksUsersMe() = runBlocking {
        CareLinkVault.save(app, session(expiresInMs = 2 * 3_600_000L, role = null))
        net.on("$CARELINK_BASE/users/me", ok("""{"role": "PATIENT"}"""))
        val r = CareLinkAuth.ensureFresh(app) as FreshResult.Ok
        assertEquals("PATIENT", r.session.role)
        assertEquals("PATIENT", CareLinkVault.load(app)!!.role)
    }

    // ---------- sign-in ----------

    private fun cloudForSignIn(role: String = "PATIENT") {
        net.on(CareLinkConfig.DISCOVERY_URL, ok(CareLinkTestData.DISCOVERY))
        net.on(SSO_URL, ok(CareLinkTestData.SSO))
        net.on(
            TOKEN_URL,
            ok("""{"access_token": "${access(now / 1000 + 10_775)}", "refresh_token": "made-up-refresh-1", "expires_in": 10775}"""),
        )
        net.on("$CARELINK_BASE/users/me", ok("""{"role": "$role", "firstName": null}"""))
        net.on("$CARELINK_BASE/links/patients", ok("""[{"username": "patient-a"}, {"username": "patient-b"}]"""))
    }

    private suspend fun begin(): Pair<String, String> {
        val start = CareLinkAuth.beginLogin(app) as LoginStart.Ready
        val state = start.uri.getQueryParameter("state")!!
        return state to start.redirectUri
    }

    @Test
    fun fullSignInStoresTheSessionAndCallsTheListener() = runBlocking {
        cloudForSignIn()
        var called: Context? = null
        CareLinkAuth.onLoginSuccess = { called = it }
        val (state, redirect) = begin()
        assertEquals(CareLinkTestData.REDIRECT, redirect)
        assertEquals(24, state.length) // 18 random bytes, base64url without padding
        assertTrue(pendingFile.exists())
        assertFalse(pendingFile.readText().contains("made-up-refresh"))
        assertEquals(redirect, CareLinkAuth.pendingRedirectUri(app))

        val fail = CareLinkAuth.finishLogin(app, "$redirect?code=one-time-code&state=$state")
        assertNull(fail)
        val form = net.forms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("one-time-code", form["code"])
        assertEquals(redirect, form["redirect_uri"])
        val s = CareLinkVault.load(app)!!
        assertEquals("tester", s.username)
        assertEquals("DE", s.country)
        assertEquals("EU", s.region)
        assertEquals("PATIENT", s.role)
        assertNull(s.patientId)
        assertEquals(now + 10_775_000L, s.expiresAtMs)
        assertNotNull(called)
        assertFalse(pendingFile.exists())
        assertNull(CareLinkAuth.pendingRedirectUri(app))
        // discovery was asked once (cached for an hour)
        assertEquals(1, net.calls.count { it == "GET ${CareLinkConfig.DISCOVERY_URL}" })
    }

    @Test
    fun carePartnerGetsThePatientName() = runBlocking {
        cloudForSignIn(role = "CARE_PARTNER_OUS")
        val (state, redirect) = begin()
        assertNull(CareLinkAuth.finishLogin(app, "$redirect?code=c&state=$state"))
        assertEquals("patient-a", CareLinkVault.load(app)!!.patientId)
    }

    @Test
    fun wrongMarkOrPageErrorDoesNotSignIn() = runBlocking {
        cloudForSignIn()
        val (state, redirect) = begin()
        assertEquals(LoginFail.STATE_MISMATCH, CareLinkAuth.finishLogin(app, "$redirect?code=c&state=other"))
        assertEquals(LoginFail.DENIED, CareLinkAuth.finishLogin(app, "$redirect?error=access_denied&state=$state"))
        assertFalse(CareLinkAuth.hasSession(app))
        assertTrue(net.forms.isEmpty())
    }

    @Test
    fun noRefreshKeyOrNoUserNameIsAFailedSignIn() = runBlocking {
        cloudForSignIn()
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_775)}", "expires_in": 10775}"""))
        var (state, redirect) = begin()
        assertEquals(LoginFail.BAD_ANSWER, CareLinkAuth.finishLogin(app, "$redirect?code=c&state=$state"))
        assertFalse(CareLinkAuth.hasSession(app))

        val noUser = CareLinkTestData.jwt("""{"exp": ${now / 1000 + 10_775}, "token_details": {"country": "DE"}}""")
        net.on(TOKEN_URL, ok("""{"access_token": "$noUser", "refresh_token": "made-up-refresh-1"}"""))
        begin().also { state = it.first; redirect = it.second }
        assertEquals(LoginFail.BAD_ANSWER, CareLinkAuth.finishLogin(app, "$redirect?code=c&state=$state"))
        assertFalse(CareLinkAuth.hasSession(app))
    }

    @Test
    fun noPendingSignInIsRefused() = runBlocking {
        cloudForSignIn()
        assertEquals(LoginFail.NO_PENDING, CareLinkAuth.finishLogin(app, "${CareLinkTestData.REDIRECT}?code=c&state=s"))
        assertFalse(CareLinkAuth.hasSession(app))
    }

    @Test
    fun onlyAuth0SignInStarts() = runBlocking {
        net.on(CareLinkConfig.DISCOVERY_URL, ok(CareLinkTestData.DISCOVERY_NO_AUTH0))
        assertEquals(LoginStart.Failed(LoginFail.UNSUPPORTED, 0), CareLinkAuth.beginLogin(app))
        assertFalse(pendingFile.exists())
    }

    @Test
    fun noNetworkBeforeTheSignInPage() = runBlocking {
        net.on(CareLinkConfig.DISCOVERY_URL, NO_NETWORK)
        assertEquals(LoginStart.Failed(LoginFail.NO_NETWORK, 0), CareLinkAuth.beginLogin(app))
        net.on(CareLinkConfig.DISCOVERY_URL, status(503))
        assertEquals(LoginStart.Failed(LoginFail.SERVER, 503), CareLinkAuth.beginLogin(app))
    }

    // ---------- sign-out ----------

    @Test
    fun signOutWipesSessionAndPendingAndCallsTheListener() = runBlocking {
        cloudForSignIn()
        CareLinkVault.save(app, session(expiresInMs = 2 * 3_600_000L))
        CareLinkAuth.ensureFresh(app) // session in memory too
        begin()
        assertTrue(pendingFile.exists())
        var called = false
        CareLinkAuth.onSignOut = { called = true }
        CareLinkAuth.signOut(app)
        assertTrue(called)
        assertFalse(CareLinkAuth.hasSession(app))
        assertFalse(pendingFile.exists())
        assertEquals(FreshResult.NeedLogin, CareLinkAuth.ensureFresh(app))
    }
}
