package rus.carelink

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import rus.carelink.CareLinkTestData.TOKEN_URL
import rus.carelink.CareLinkTestData.access
import rus.carelink.CareLinkTestData.ok
import rus.carelink.CareLinkTestData.status
import tk.glucodata.rus.carelink.AesGcmSessionCipher
import tk.glucodata.rus.carelink.CareLinkAuth
import tk.glucodata.rus.carelink.CareLinkErrorKind
import tk.glucodata.rus.carelink.CareLinkNotifier
import tk.glucodata.rus.carelink.CareLinkPoller
import tk.glucodata.rus.carelink.CareLinkScheduler
import tk.glucodata.rus.carelink.CareLinkSession
import tk.glucodata.rus.carelink.CareLinkSettings
import tk.glucodata.rus.carelink.CareLinkState
import tk.glucodata.rus.carelink.CareLinkStateStore
import tk.glucodata.rus.carelink.CareLinkStatus
import tk.glucodata.rus.carelink.CareLinkVault
import tk.glucodata.rus.carelink.ClEvent
import tk.glucodata.rus.carelink.PollReason
import tk.glucodata.rus.carelink.PumpNow
import java.time.ZoneOffset
import javax.crypto.KeyGenerator

/**
 * JugglucoNG-RUS, CareLink 4c: one poll against a fake network - renewal on 401 and one more try, `invalid_grant` ->
 * sign-in needed and a notification, no network -> error with nothing wiped, a failed writer or an empty answer does not
 * move the data time, when a poll is due, the "no data" notification. Made-up answers, user and doses only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkPollerTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val net = FakeCareLinkHttp()
    private val work = FakeCareLinkWork()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cumulus = "https://cumulus.example.eu/connect/carepartner/v13"
    private val dm = "$cumulus/display/message"

    private val journal = mutableListOf<Pair<List<ClEvent>, Boolean>>()
    private val basal = mutableListOf<Pair<PumpNow?, Long>>()
    private val iob = mutableListOf<PumpNow?>()

    private fun session(role: String = "PATIENT", patientId: String? = null) = CareLinkSession(
        accessToken = access((now + 2 * 3_600_000L) / 1000), refreshToken = "made-up-refresh-1",
        expiresAtMs = now + 2 * 3_600_000L, username = "tester", role = role, patientId = patientId, country = "DE",
        region = "EU", baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = cumulus, tokenUrl = TOKEN_URL,
        clientId = "abc123", loggedInAtMs = now - 3_600_000L,
    )

    /** A made-up answer: one finished 2 U bolus, pump data [lagMin] min before now. */
    private fun answer(lagMin: Long = 3, reservoir: String = "150.0", battery: String = "60") = """
        {"metadata": {"kind": "BLENGP", "clientDateTime": "2027-01-15T11:00:00.000+03:00"},
         "patientData": {
           "lastMedicalDeviceDataUpdateServerTime": ${now - lagMin * 60_000L},
           "activeInsulin": {"amount": 1.5}, "reservoirRemainingUnits": $reservoir, "pumpBatteryLevelPercent": $battery,
           "basal": {"basalRate": 0.8},
           "markers": [{"type": "INSULIN", "timestamp": "2027-01-15T10:30:00",
                        "data": {"dataValues": {"programmedFastAmount": 2.0, "deliveredFastAmount": 2.0, "completed": true}}}]
         }}
    """.trimIndent()

    private val store get() = CareLinkStateStore(app)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun shown(id: Int): Notification? = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    private fun poll(reason: PollReason = PollReason.MANUAL) = runBlocking { CareLinkPoller.poll(app, reason) }

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkAuth.http = net
        CareLinkAuth.clock = { now }
        CareLinkScheduler.work = work
        CareLinkPoller.zone = { ZoneOffset.ofHours(5) }
        CareLinkPoller.writeJournal = { e, first -> journal += e to first }
        CareLinkPoller.writeBasal = { p, at -> basal += p to at }
        CareLinkPoller.publishIob = { p -> iob += p }
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    @Test
    fun goodPollWritesAndMovesTheDataTime() {
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(now, s.lastPollAtMs)
        assertEquals(now - 3 * 60_000L, s.lastDataAtMs)
        assertNull(s.lastError)
        assertEquals(150.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(listOf("JSON $dm"), net.calls)
        val body = JSONObject(net.jsons.single())
        assertEquals("tester", body.getString("username"))
        assertEquals("patient", body.getString("role"))
        assertFalse(body.has("patientId"))
        assertEquals(1, journal.single().first.size)
        assertTrue("no data written before: the whole window", journal.single().second)
        assertEquals(now, basal.single().second)
        assertEquals(1.5, basal.single().first!!.iobUnits!!, 1e-9)
        assertEquals(1.5, iob.single()!!.iobUnits!!, 1e-9)
        assertEquals("state is stored", s, store.read())
        // the next poll is no longer the first
        poll()
        assertFalse(journal.last().second)
    }

    @Test
    fun carePartnerAsksForThePatient() {
        CareLinkVault.save(app, session(role = "CARE_PARTNER", patientId = "made-up-patient"))
        net.on(dm, ok(answer()))
        poll()
        val body = JSONObject(net.jsons.single())
        assertEquals("carepartner", body.getString("role"))
        assertEquals("made-up-patient", body.getString("patientId"))
    }

    @Test
    fun refusedKeyIsRenewedAndTheRequestRepeatedOnce() {
        net.on(dm, status(401), ok(answer()))
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800, "refresh_token": "made-up-refresh-2"}"""))
        val s = poll()
        assertEquals(listOf("JSON $dm", "FORM $TOKEN_URL refresh_token", "JSON $dm"), net.calls)
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(1, journal.size)
    }

    @Test
    fun refusedAgainAfterRenewalIsAnErrorAndTheSessionStays() {
        net.on(dm, status(403))
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800}"""))
        val s = poll()
        assertEquals(3, net.calls.size)
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.SERVER, s.lastError!!.kind)
        assertEquals(403, s.lastError!!.code)
        assertTrue(CareLinkVault.exists(app))
        assertTrue(journal.isEmpty())
    }

    @Test
    fun invalidGrantNeedsSignInNotifiesOnceAndStopsTheSchedule() {
        net.on(dm, status(401))
        net.on(TOKEN_URL, status(400, """{"error": "invalid_grant", "error_description": "made-up"}"""))
        val s = poll()
        assertEquals(CareLinkStatus.NEED_LOGIN, s.status)
        assertFalse("session wiped", CareLinkVault.exists(app))
        assertNotNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        assertTrue("alarms and safety work off", work.log.contains("cancel"))
        // no session: the next poll does nothing, the notification is not posted again after a swipe
        nm.cancel(CareLinkNotifier.ID_NEED_LOGIN)
        net.calls.clear()
        val again = poll()
        assertTrue(net.calls.isEmpty())
        assertEquals(CareLinkStatus.NEED_LOGIN, again.status)
        CareLinkNotifier.needLogin(app)
        assertNull("once until a sign-in", shown(CareLinkNotifier.ID_NEED_LOGIN))
        CareLinkNotifier.loggedIn(app)
        CareLinkNotifier.needLogin(app)
        assertNotNull("after a sign-in it may come again", shown(CareLinkNotifier.ID_NEED_LOGIN))
    }

    private fun seed(dataAt: Long, pump: PumpNow? = PumpNow(dataAt, reservoirUnits = 90.0, basalRate = 0.6)) =
        store.write(CareLinkState(CareLinkStatus.OK, now - 5 * 60_000L, dataAt, null, pump))

    @Test
    fun noNetworkIsAnErrorAndNothingIsWiped() {
        val before = now - 10 * 60_000L
        seed(before)
        net.on(dm, NO_NETWORK)
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.NO_NETWORK, s.lastError!!.kind)
        assertEquals("IOException", s.lastError!!.errorClass)
        assertEquals(before, s.lastDataAtMs)
        assertEquals(90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(now, s.lastPollAtMs)
        assertTrue(CareLinkVault.exists(app))
        assertTrue(journal.isEmpty())
    }

    @Test
    fun failedJournalWriteDoesNotMoveTheDataTime() {
        val before = now - 10 * 60_000L
        seed(before)
        CareLinkPoller.writeJournal = { _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("IllegalStateException", s.lastError!!.errorClass)
        assertEquals(before, s.lastDataAtMs)
        assertEquals("snapshot unchanged", 90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertTrue("basal not written after a failed journal", basal.isEmpty())
        assertTrue(iob.isEmpty())
    }

    @Test
    fun failedBasalWriteDoesNotMoveTheDataTime() {
        val before = now - 10 * 60_000L
        seed(before)
        CareLinkPoller.writeBasal = { _, _ -> throw java.io.IOException("made-up") }
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals(before, s.lastDataAtMs)
    }

    @Test
    fun brokenAnswerIsABadAnswer() {
        net.on(dm, ok("{}"), ok("not json"), status(500))
        assertEquals(CareLinkErrorKind.BAD_ANSWER, poll().lastError!!.kind)
        assertEquals(CareLinkErrorKind.BAD_ANSWER, poll().lastError!!.kind)
        val s = poll()
        assertEquals(CareLinkErrorKind.SERVER, s.lastError!!.kind)
        assertEquals(500, s.lastError!!.code)
    }

    @Test
    fun emptyAnswerKeepsTheSnapshotAndTheDataTime() {
        val before = now - 10 * 60_000L
        seed(before)
        net.on(dm, ok(answer(lagMin = 1, reservoir = "-1", battery = "-1")))
        val s = poll()
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(before, s.lastDataAtMs)
        assertEquals(90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertNull("the basal writer sees a gap", basal.single().first)
        assertNull(iob.single())
        assertEquals("markers are still written", 1, journal.single().first.size)
    }

    @Test
    fun dataTimeNeverMovesBack() {
        val before = now - 2 * 60_000L
        seed(before)
        net.on(dm, ok(answer(lagMin = 20)))
        val s = poll()
        assertEquals(before, s.lastDataAtMs)
        assertEquals("an older snapshot is not taken", 90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
    }

    @Test
    fun alarmAndSafetyWorkPollOnlyWhenDue() {
        net.on(dm, ok(answer()))
        store.write(CareLinkState(CareLinkStatus.OK, now - 2 * 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.ALARM)
        poll(PollReason.WORKER)
        assertTrue("5-min interval, 2 min ago - not due", net.calls.isEmpty())
        store.write(CareLinkState(CareLinkStatus.OK, now - 270_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.ALARM)
        assertEquals("4.5 min ago - due (a minute of slack)", 1, net.calls.size)
        CareLinkSettings(app).intervalMin = 15
        store.write(CareLinkState(CareLinkStatus.OK, now - 10 * 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.WORKER)
        assertEquals("15-min interval, 10 min ago - not due", 1, net.calls.size)
    }

    @Test
    fun appOpeningPollsNotMoreOftenThanEveryTwoMinutes() {
        net.on(dm, ok(answer()))
        store.write(CareLinkState(CareLinkStatus.OK, now - 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.FOREGROUND)
        assertTrue(net.calls.isEmpty())
        poll(PollReason.MANUAL)
        assertEquals("the button always polls", 1, net.calls.size)
        now += 3 * 60_000L
        poll(PollReason.FOREGROUND)
        assertEquals(2, net.calls.size)
    }

    @Test
    fun offOrSignedOutDoesNothing() {
        net.on(dm, ok(answer()))
        CareLinkSettings(app).enabled = false
        assertEquals(CareLinkStatus.OFF, poll().status)
        CareLinkSettings(app).enabled = true
        CareLinkVault.clear(app)
        poll(PollReason.LOGIN)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun noPumpDataFor30MinNotifiesOnceAndGoesAwayWithData() {
        seed(now - 31 * 60_000L)
        net.on(dm, NO_NETWORK)
        poll()
        val n = shown(CareLinkNotifier.ID_NO_DATA)
        assertNotNull(n)
        assertTrue(n!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("31"))
        // swiped away: not posted again while the data is still missing
        nm.cancel(CareLinkNotifier.ID_NO_DATA)
        now += 5 * 60_000L
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        // the alarm checks without a poll too: still missing, still not posted again
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        // data is back: the mark is cleared; a later gap notifies again
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        now += 40 * 60_000L
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // and goes away by itself when data returns
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun noDataNotificationOnlyWhileSignedInAndOn() {
        seed(now - 60 * 60_000L)
        CareLinkSettings(app).enabled = false
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkSettings(app).enabled = true
        CareLinkVault.clear(app)
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkVault.save(app, session())
        store.write(CareLinkState(CareLinkStatus.OK, now, null, null, null))
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull("never any data since the sign-in - the window shows it", shown(CareLinkNotifier.ID_NO_DATA))
    }
}
