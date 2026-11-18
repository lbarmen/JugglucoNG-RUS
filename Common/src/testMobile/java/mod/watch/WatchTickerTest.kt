package mod.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.WatchTickWorker
import tk.glucodata.mod.watch.WatchTicker

/** JugglucoNGmod, watch block: when the phone ticks the watch - cycle step and the battery extra tick. Made-up values. */
class WatchTickerTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun theNextCycleStepIsFiveMinutesWithAWatchAndAnHourWithout() {
        assertEquals(5 * minute, WatchTickWorker.nextDelayMs(watchFound = true))
        assertEquals(60 * minute, WatchTickWorker.nextDelayMs(watchFound = false))
        assertEquals("mod-watch-tick", WatchTickWorker.WORK)
    }

    @Test
    fun aBatteryMoveOfFivePointsSendsAnExtraTick() {
        val longAgo = now - 10 * minute
        assertTrue(WatchTicker.shouldSendForBattery(55, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(65, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(30, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(0, 5, now, longAgo))
    }

    @Test
    fun aSmallerMoveSendsNothing() {
        val longAgo = now - 10 * minute
        for (percent in 56..64) assertFalse("percent $percent", WatchTicker.shouldSendForBattery(percent, 60, now, longAgo))
    }

    @Test
    fun noTickReachedAWatchYetSendsNothing() {
        assertFalse(WatchTicker.shouldSendForBattery(10, null, now, 0L))
        assertFalse(WatchTicker.shouldSendForBattery(90, null, now, now - 10 * minute))
    }

    @Test
    fun notMoreThanOnceAMinute() {
        assertFalse(WatchTicker.shouldSendForBattery(40, 60, now, now))
        assertFalse(WatchTicker.shouldSendForBattery(40, 60, now, now - minute + 1))
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now - minute))
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now - 3 * minute))
        // the phone clock set back: not held for the gap
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now + 5 * minute))
    }

    @Test
    fun theGapRule() {
        assertFalse(WatchTicker.gapPassed(now, now))
        assertFalse(WatchTicker.gapPassed(now, now - WatchTicker.MIN_EXTRA_GAP_MS + 1))
        assertTrue(WatchTicker.gapPassed(now, now - WatchTicker.MIN_EXTRA_GAP_MS))
        assertTrue(WatchTicker.gapPassed(now, 0L))
        assertEquals(minute, WatchTicker.MIN_EXTRA_GAP_MS)
        assertEquals(5, WatchTicker.BATTERY_STEP)
    }
}
