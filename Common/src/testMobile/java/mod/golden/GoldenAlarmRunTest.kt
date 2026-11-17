package mod.golden

import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertEpisodeState
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.ForecastLowSuppression
import tk.glucodata.alerts.StandardGlucoseAlertEvaluator
import tk.glucodata.data.prediction.GlucosePredictionKernel
import tk.glucodata.logic.TrendEngine
import java.io.File
import java.util.Locale

/**
 * JugglucoNGmod golden runs of upstream's alarm and forecast decisions (plan of stage 2, B3; ТЗ 3.5).
 *
 * Each synthetic series of mod/golden/scenarios/ (mod/golden/scenarios.py, no real readings) is fed minute by
 * minute through upstream's own code, the way their tests do it: rate from TrendEngine, alarm conditions from
 * StandardGlucoseAlertEvaluator with default settings (forecast alarms switched on - they are off by default),
 * episodes from AlertEpisodeState, the forecast from GlucosePredictionKernel (trend momentum on, the default; no
 * journal). The decisions are compared with mod/golden/expected/<name>.csv; a difference fails with a table.
 *
 * Not covered (see the sync report): showing and sounding the alarm, quiet hours, snooze, same-direction
 * suppression, IOB/COB evidence for forecast low, real Sibionics signal gaps.
 *
 * Update the expected files only after a review in the sync chat, with a line in mod/SYNC-LOG.md:
 * MOD_GOLDEN_UPDATE=1 writes them instead of comparing. Every run writes Common/build/mod-golden/actual/ and diff.md.
 */
class GoldenAlarmRunTest {
    private val start = 1_700_000_000_000L
    private val minuteMs = 60_000L
    private val order = listOf(
        AlertType.VERY_LOW, AlertType.LOW, AlertType.VERY_HIGH, AlertType.HIGH, AlertType.PRE_LOW, AlertType.PRE_HIGH
    )
    private val header = "minute,value,rate,very_low,low,very_high,high,pre_low,pre_low_shown,pre_high,entered,pred15,pred30"

    private fun goldenDir(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val g = File(d, "mod/golden")
            if (File(g, "scenarios").isDirectory) return g
            d = d.parentFile
        }
        error("mod/golden/scenarios not found above ${System.getProperty("user.dir")}")
    }

    private fun num(x: Float, digits: Int): String {
        if (!x.isFinite()) return "nan"
        val s = String.format(Locale.ROOT, "%.${digits}f", x)
        return if (s.startsWith("-") && s.all { it == '-' || it == '0' || it == '.' }) s.substring(1) else s
    }

    private fun configs(isMmol: Boolean): Map<AlertType, AlertConfig> = order.associateWith { type ->
        val c = AlertDefaults.defaultConfig(type, isMmol)
        if (type == AlertType.PRE_LOW || type == AlertType.PRE_HIGH) c.copy(enabled = true) else c
    }

    internal fun run(points: List<Pair<Int, Float>>, isMmol: Boolean): List<String> {
        val cfg = configs(isMmol)
        val episodes = AlertEpisodeState<AlertType>()
        val history = mutableListOf<GlucosePoint>()
        val rows = mutableListOf(header)
        val digits = if (isMmol) 2 else 1
        val targetLow = if (isMmol) 3.9f else 70f
        val targetHigh = if (isMmol) 10.0f else 180f
        for ((minute, value) in points) {
            val time = start + minute * minuteMs
            history.add(GlucosePoint(time, value))
            val window = history.filter { time - it.timestamp <= 60 * minuteMs }
            val rate = TrendEngine.calculateTrend(window, false, isMmol).velocity
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = value,
                rate = rate,
                configs = cfg,
                alertTypes = order,
                isMmol = isMmol,
                isConfigActive = { true },
                wasConditionActive = episodes::isActive,
                forecastRateTrusted = true
            )
            val transition = episodes.update(active.keys)
            val prediction = GlucosePredictionKernel.simulate(
                history = window.map { GlucosePredictionKernel.Sample(it.timestamp, it.value) },
                isMmol = isMmol,
                trendMomentumEnabled = true,
                horizonMinutes = 30,
                stepMinutes = 5,
                targetLow = targetLow,
                targetHigh = targetHigh,
                journalDeltaAt = { 0f }
            )
            fun at(m: Int) = prediction.firstOrNull { it.timestamp == time + m * minuteMs }?.value ?: Float.NaN
            fun on(t: AlertType) = if (t in active) "1" else "0"
            val preLowShown = AlertType.PRE_LOW in active && ForecastLowSuppression.hasDownwardArrow(rate)
            val entered = order.filter { it in transition.entered }.joinToString("+") { it.name.lowercase() }
            rows += listOf(
                minute.toString(), num(value, digits), num(rate, 2),
                on(AlertType.VERY_LOW), on(AlertType.LOW), on(AlertType.VERY_HIGH), on(AlertType.HIGH),
                on(AlertType.PRE_LOW), if (preLowShown) "1" else "0", on(AlertType.PRE_HIGH),
                entered.ifEmpty { "-" }, num(at(15), digits), num(at(30), digits)
            ).joinToString(",")
        }
        return rows
    }

    private fun readScenario(f: File): List<Pair<Int, Float>> =
        f.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val (m, v) = line.split(",")
            m.trim().toInt() to v.trim().toFloat()
        }

    private fun diffTable(name: String, expected: List<String>, actual: List<String>): List<String> {
        val cols = header.split(",")
        val exp = expected.drop(1).associateBy { it.substringBefore(",") }
        val act = actual.drop(1).associateBy { it.substringBefore(",") }
        val out = mutableListOf<String>()
        var count = 0
        for (minute in (exp.keys + act.keys).distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
            val e = exp[minute]?.split(",")
            val a = act[minute]?.split(",")
            for (i in cols.indices.drop(1)) {
                val ev = e?.getOrNull(i) ?: "(нет)"
                val av = a?.getOrNull(i) ?: "(нет)"
                if (ev != av) {
                    count++
                    if (out.size < 25) out += "| $name | $minute | ${cols[i]} | $ev | $av |"
                }
            }
        }
        if (count > out.size) out += "| $name | … | ещё ${count - out.size} | | |"
        return out
    }

    @Test
    fun upstreamAlarmAndForecastDecisionsMatchTheGoldenFiles() {
        val golden = goldenDir()
        val scenarios = File(golden, "scenarios").listFiles { f -> f.name.endsWith(".csv") }!!.sortedBy { it.name }
        val expectedDir = File(golden, "expected")
        val outDir = File(golden.parentFile.parentFile, "Common/build/mod-golden")
        val actualDir = File(outDir, "actual").apply { mkdirs() }
        val update = System.getenv("MOD_GOLDEN_UPDATE") == "1"
        val problems = mutableListOf<String>()
        val table = mutableListOf<String>()
        for (f in scenarios) {
            val name = f.name.removeSuffix(".csv")
            val actual = run(readScenario(f), isMmol = name.endsWith("_mmol"))
            File(actualDir, f.name).writeText(actual.joinToString("\n") + "\n")
            val exp = File(expectedDir, f.name)
            if (update) {
                expectedDir.mkdirs()
                exp.writeText(actual.joinToString("\n") + "\n")
                continue
            }
            if (!exp.isFile) {
                problems += "$name: эталона нет (mod/golden/expected/${f.name})"
                continue
            }
            val expected = exp.readLines().filter { it.isNotBlank() }
            if (expected != actual) {
                problems += "$name: решения изменились"
                table += diffTable(name, expected, actual)
            }
        }
        if (!update) {
            val names = scenarios.map { it.name }.toSet()
            expectedDir.listFiles { f -> f.name.endsWith(".csv") && f.name !in names }?.forEach {
                problems += "${it.name}: эталон без серии"
            }
        }
        val report = buildList {
            add("# Эталоны тревог и прогноза")
            add("")
            add(if (problems.isEmpty()) "Совпали: ${scenarios.size} серий." else "**Расхождения:**")
            problems.forEach { add("- $it") }
            if (table.isNotEmpty()) {
                add("")
                add("| серия | минута | столбец | эталон | сейчас |")
                add("|---|---|---|---|---|")
                addAll(table)
            }
        }
        File(outDir, "diff.md").writeText(report.joinToString("\n") + "\n")
        if (problems.isNotEmpty()) fail(report.joinToString("\n"))
    }
}
