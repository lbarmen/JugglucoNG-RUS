package mod.watch

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.CoalescedRunner
import tk.glucodata.mod.watch.SignalBudget

/** Part 7o: the read budget and the early alert check of the watch. */
class FreshReadingAlertsTest {

    private fun now() = System.nanoTime() / 1_000_000L

    @Test
    fun theBudgetLetsCountRunsThroughAWindow() {
        val budget = SignalBudget(3, 20_000L)
        val t = 5_000_000L
        assertEquals(0L, budget.waitMs(t))
        budget.record(t)
        budget.record(t + 100L)
        assertEquals(0L, budget.waitMs(t + 200L))
        budget.record(t + 200L)
        assertEquals(20_000L - 300L, budget.waitMs(t + 300L))
        assertEquals(0L, budget.waitMs(t + 20_000L))
        // the oldest run leaves: the next waits for the second oldest
        budget.record(t + 20_000L)
        assertEquals(100L, budget.waitMs(t + 20_000L))
    }

    @Test
    fun signalsWhileARunWaitsAreOneRun() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now) {
            runs.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        runner.signal()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // during the run: one more after it, however many signals
        repeat(10) { runner.signal() }
        release.countDown()
        Thread.sleep(300L)
        assertEquals(2, runs.get())
    }

    @Test
    fun aCatchUpKeepsToTheBudgetAndRunsAgainOnceTheWindowLetsIt() {
        val runs = AtomicInteger()
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val fourth = CountDownLatch(4)
        val runner = CoalescedRunner("test", SignalBudget(3, 1_000L), ::now) {
            times += now()
            runs.incrementAndGet()
            fourth.countDown()
            Thread.sleep(30L)
        }
        // 60 signals in about 300 ms (a busy wait: Thread.sleep is coarse on Windows)
        repeat(60) {
            runner.signal()
            val until = System.nanoTime() + 5_000_000L
            while (System.nanoTime() < until) Unit
        }
        // the window of 1 s is not over: three at most
        assertTrue("runs ${runs.get()}", runs.get() in 1..3)
        assertTrue(fourth.await(5, TimeUnit.SECONDS))
        assertTrue("times $times", times[3] - times[0] >= 990L)
        Thread.sleep(1_200L)
        // the signals were coalesced: no run for each
        assertTrue("runs ${runs.get()}", runs.get() <= 5)
    }

    @Test
    fun aFailingActionDoesNotStopTheRunner() {
        val runs = AtomicInteger()
        val second = CountDownLatch(2)
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now) {
            runs.incrementAndGet()
            second.countDown()
            throw IllegalStateException("upstream failed")
        }
        runner.signal()
        Thread.sleep(200L)
        runner.signal()
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals(2, runs.get())
    }

    @Test
    fun theWatchStartsTheCheckFromOurProviderAndCallsOnlyUpstreamsPublicEntry() {
        fun file(path: String) = listOf(File("src/$path"), File("Common/src/$path")).first { it.isFile }.readText()
        val starter = file("wearMod/java/tk/glucodata/mod/health/hs/ModHealthStarter.kt")
        assertTrue(starter.contains("FreshReadingAlerts.startAfterApplication()"))
        val alerts = file("wear/java/tk/glucodata/mod/watch/FreshReadingAlerts.kt")
        assertTrue(alerts.contains("AlertRuntimeManager.ensureMonitoring()"))
        assertTrue(alerts.contains("UiRefreshBus.revision"))
        // no value or time of an event in the journal
        val lines = Regex("""ModWatchLog\.[iw]\("([^"]*)"""").findAll(alerts).map { it.groupValues[1] }.toList()
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertFalse(it, it.contains("value") || it.contains("glucose")) }
    }
}
