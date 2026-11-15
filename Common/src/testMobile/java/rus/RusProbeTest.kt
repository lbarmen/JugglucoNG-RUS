package rus

import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.StandardGlucoseAlertEvaluator

/**
 * JugglucoNG-RUS, plan of stage 2, B2: our folder Common/src/testMobile/java/rus/ is compiled and run by upstream's
 * own task testMobileDebugUnitTest, and `internal` upstream classes are visible from it (same module).
 */
class RusProbeTest {
    @Test
    fun ourTestsRunInUpstreamTaskAndSeeInternalClasses() {
        assertTrue(StandardGlucoseAlertEvaluator.defaultThresholdRearmMargin(false) > 0f)
    }
}
