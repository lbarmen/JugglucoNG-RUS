package mod.watch

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.WearAlertSync
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.mod.watch.OngoingAlert
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: the alert going on and its snooze ([OngoingAlert]) against upstream's real action
 * handler, tracker and snooze store - the way upstream's WearAlertSyncTests runs them. Only native audio and
 * notification delivery is a recording sink. Runs in both test tasks (phone and watch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OngoingAlertTest.TestApplication::class,
    shadows = [OngoingAlertTest.AlarmSink::class, OngoingAlertTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OngoingAlertTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
        override fun startActivity(intent: android.content.Intent) {}
    }

    @Implements(Notify::class)
    class AlarmSink {
        companion object {
            val calls = mutableListOf<String>()
            /** The presented standard alert, -1 = none. */
            var currentKind = -1
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun cancelRetrySession(kind: Int, reason: String) { calls += "retry:$kind" }
            @JvmStatic @Implementation fun cancelQueuedAlarmActivityLaunch(kind: Int, custom: String?, reason: String) {}
            @JvmStatic @Implementation fun stopalarm() { calls += "stop" }
            @JvmStatic @Implementation fun stopalarmnotsend(send: Boolean) { calls += "remote-stop:$send" }
            @JvmStatic @Implementation fun cancelAlertNotification() { calls += "notification" }
            @JvmStatic @Implementation fun isPresentedStandardAlert(kind: Int) = kind == currentKind
            @JvmStatic @Implementation fun isCurrentStandardAlert(kind: Int) = kind >= 0 && kind == currentKind
            @JvmStatic @Implementation fun resolveAlertKind(fallback: Int) = fallback
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink

    private val acknowledged = mutableListOf<Int>()
    private val observer = Any()

    companion object {
        /**
         * Upstream's alert state as a fresh install, before and after each test. SnoozeManager keeps the preferences
         * of the first test application it saw (`by lazy`), so clearing the current application's file is not
         * enough: a snooze left there would leak into the next test class (upstream's WearAlertSyncTests included).
         */
        fun resetAlertState() {
            Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
            Applic.app.getSharedPreferences("wear_alert_ack", 0).edit().clear().commit()
            Applic.app.getSharedPreferences("tk.glucodata.snooze", 0).edit().clear().commit()
            AlertType.entries.forEach {
                SnoozeManager.clearSnooze(it, recordCancellation = false)
                AlertStateTracker.resetState(it)
                AlertStateTracker.consumeManualTestAction(it)
            }
            AlarmSink.calls.clear()
            AlarmSink.currentKind = -1
            Log.doLog = false
            AlarmActivityAccess.register { android.content.Intent("test.alarm") }
        }
    }

    @Before
    fun setUp() {
        resetAlertState()
        acknowledged.clear()
    }

    @After
    fun tearDown() {
        AlarmActivityAccess.removeObserver(observer)
        resetAlertState()
    }

    /** The full-screen alarm for [kind] is open: it records when it is told to close. */
    private fun alarmScreenOpenFor(kind: Int) {
        AlarmActivityAccess.observeAcknowledgement(observer, kind) { acknowledged += kind }
    }

    @Test
    fun noAlertGoingOnFindsNothingAndCallsNothing() {
        alarmScreenOpenFor(AlertType.HIGH.id)
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertTrue(AlarmSink.calls.isEmpty())
        assertTrue(acknowledged.isEmpty())
        for (type in AlertType.settingsEntries) {
            assertFalse(type.name, SnoozeManager.isSnoozed(type))
            assertFalse(type.name, WearAlertSync.isSuppressed(type.id))
        }
    }

    @Test
    fun findReturnsThePresentedStandardAlert() {
        for (type in AlertType.settingsEntries) {
            AlarmSink.currentKind = type.id
            assertEquals(type.name, type.id, OngoingAlert.find())
        }
    }

    @Test
    fun legacyOnlyKindsAreNeverPickedUp() {
        AlarmSink.currentKind = AlertType.AVAILABLE.id
        assertNull(OngoingAlert.find())
        AlarmSink.currentKind = AlertType.AMOUNT.id
        assertNull(OngoingAlert.find())
    }

    @Test
    fun snoozeIsUpstreamsSnoozeAndClosesTheAlarmScreen() {
        AlarmSink.currentKind = AlertType.LOW.id
        alarmScreenOpenFor(AlertType.LOW.id)
        val before = System.currentTimeMillis()
        assertEquals(AlertType.LOW.id, OngoingAlert.snoozeOngoing())
        val after = System.currentTimeMillis()

        assertTrue(SnoozeManager.isSnoozed(AlertType.LOW))
        assertTrue(WearAlertSync.isSuppressed(AlertType.LOW.id)) // their /alertack record for the other device
        assertTrue(AlarmSink.calls.indexOf("retry:0") in 0 until AlarmSink.calls.indexOf("stop"))
        assertTrue(AlarmSink.calls.contains("notification"))
        assertEquals(listOf(AlertType.LOW.id), acknowledged)
        // the alert's own default snooze, as a plain "Snooze" on the phone
        val minutes = AlertActions.defaultSnoozeMinutes(AlertType.LOW.id, null)
        val until = SnoozeManager.getSnoozeState(AlertType.LOW)!!.snoozeUntilMillis
        assertTrue(until >= before + minutes * 60_000L - 1_000L)
        assertTrue(until <= after + minutes * 60_000L + 1_000L)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    @Test
    fun theAlarmScreenOfAnotherKindStaysOpen() {
        AlarmSink.currentKind = AlertType.VERY_LOW.id
        alarmScreenOpenFor(AlertType.HIGH.id)
        assertEquals(AlertType.VERY_LOW.id, OngoingAlert.snoozeOngoing())
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun aRepeatedPressKeepsOneSnooze() {
        AlarmSink.currentKind = AlertType.HIGH.id
        assertEquals(AlertType.HIGH.id, OngoingAlert.snoozeOngoing())
        val until = SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis
        AlarmSink.currentKind = -1 // their stop clears the presented alert
        assertNull(OngoingAlert.snoozeOngoing())
        assertEquals(until, SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis)
    }

    @Test
    fun offTheMainThreadSnoozeRefusesAndCallsNothing() {
        AlarmSink.currentKind = AlertType.HIGH.id
        alarmScreenOpenFor(AlertType.HIGH.id)
        var result: Boolean? = null
        val thread = Thread { result = OngoingAlert.snooze(AlertType.HIGH.id) }
        thread.start()
        thread.join(5_000)
        assertEquals(false, result)
        assertTrue(AlarmSink.calls.isEmpty())
        assertTrue(acknowledged.isEmpty())
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    @Test
    fun snoozeOngoingOnMainRunsOnTheMainThreadAndReturnsTheKind() {
        AlarmSink.currentKind = AlertType.PRE_LOW.id
        alarmScreenOpenFor(AlertType.PRE_LOW.id)
        var result: Int? = -100
        val thread = Thread { result = OngoingAlert.snoozeOngoingOnMain(10_000) }
        thread.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (thread.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        thread.join(1_000)
        assertEquals(AlertType.PRE_LOW.id, result)
        assertTrue(SnoozeManager.isSnoozed(AlertType.PRE_LOW))
        assertEquals(listOf(AlertType.PRE_LOW.id), acknowledged)
    }

    @Test
    fun snoozeOngoingOnMainWithoutAnAlertIsNull() {
        var result: Int? = -100
        val thread = Thread { result = OngoingAlert.snoozeOngoingOnMain(10_000) }
        thread.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (thread.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        thread.join(1_000)
        assertNull(result)
        assertTrue(AlarmSink.calls.isEmpty())
    }

    @Test
    fun snoozeOngoingOnMainFromTheMainThreadRunsDirectly() {
        AlarmSink.currentKind = AlertType.HIGH.id
        assertEquals(AlertType.HIGH.id, OngoingAlert.snoozeOngoingOnMain(10))
    }
}
