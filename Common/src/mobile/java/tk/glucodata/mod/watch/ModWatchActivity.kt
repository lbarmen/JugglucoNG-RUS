package tk.glucodata.mod.watch

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.ui.JugglucoTheme

/**
 * JugglucoNGmod, watch block (part 5): the check screen "JugglucoNGmod watch" - the last heart rate, today's steps,
 * the watch battery and when the last batch came, or "no data". Opened by the launcher shortcut ([ModWatchShortcut]);
 * declared only in the phone `mod` build's manifest, not exported. Where heart rate and steps go later (graph,
 * journal) is the user's decision (watch plan, decision 8).
 *
 * The data is read on a background thread ([ModWatchStore.summary]) every [REFRESH_MS] while the screen is shown.
 */
class ModWatchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent { JugglucoTheme { WatchScreen() } }
        } catch (t: Throwable) {
            ModWatchLog.w("watch screen failed: ${t.javaClass.simpleName}")
            finish()
        }
    }

    companion object {
        const val REFRESH_MS = 30_000L
    }
}

/** What the screen holds: still loading, the database did not open, or the summary. */
private sealed interface ScreenState {
    data object Loading : ScreenState
    data object NoDatabase : ScreenState
    data class Data(val summary: ModWatchIntake.Summary, val newer: Int, val nowMs: Long) : ScreenState
}

@Composable
private fun WatchScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ScreenState>(ScreenState.Loading) }
    LaunchedEffect(Unit) {
        while (true) {
            state = withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis()
                    ModWatchStore.summary(context, now)?.let { ScreenState.Data(it, ModWatchStore.newerBatches(context), now) }
                        ?: ScreenState.NoDatabase
                } catch (t: Throwable) {
                    ModWatchLog.w("watch screen read failed: ${t.javaClass.simpleName}")
                    ScreenState.NoDatabase
                }
            }
            delay(ModWatchActivity.REFRESH_MS)
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.mod_watch_screen_title), style = MaterialTheme.typography.headlineSmall)
            when (val s = state) {
                ScreenState.Loading -> Unit
                ScreenState.NoDatabase -> Line(stringResource(R.string.mod_watch_screen_no_database))
                is ScreenState.Data -> DataLines(s)
            }
        }
    }
}

@Composable
private fun DataLines(state: ScreenState.Data) {
    val context = LocalContext.current
    val s = state.summary
    val none = stringResource(R.string.mod_watch_screen_no_data)
    if (s.empty) {
        Line(none)
        Line(stringResource(R.string.mod_watch_screen_hint))
        return
    }
    fun time(ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))
    Line(
        stringResource(
            R.string.mod_watch_screen_heart_rate,
            if (s.heartRate != null && s.heartRateAt != null) {
                stringResource(R.string.mod_watch_screen_bpm_at, s.heartRate, time(s.heartRateAt))
            } else none,
        ),
    )
    Line(stringResource(R.string.mod_watch_screen_steps, s.stepsToday?.toString() ?: none))
    Line(
        stringResource(
            R.string.mod_watch_screen_battery,
            when {
                s.watchBattery == null -> none
                s.watchCharging -> stringResource(R.string.mod_watch_screen_percent_charging, s.watchBattery)
                else -> stringResource(R.string.mod_watch_screen_percent, s.watchBattery)
            },
        ),
    )
    Line(
        stringResource(
            R.string.mod_watch_screen_last_batch,
            s.lastBatchAt?.let {
                val minutes = ((state.nowMs - it) / 60_000L).coerceAtLeast(0L).toInt()
                stringResource(R.string.mod_watch_screen_minutes_ago, minutes, time(it))
            } ?: none,
        ),
    )
    if (state.newer > 0) Line(stringResource(R.string.mod_watch_screen_newer, state.newer))
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
