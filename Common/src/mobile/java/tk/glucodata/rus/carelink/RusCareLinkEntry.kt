package tk.glucodata.rus.carelink

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem

/**
 * JugglucoNG-RUS, CareLink (stage 4e): the "CareLink" row in upstream's settings, sharing section - one line there
 * (hook `carelink-settings` in ExpressiveSettingsScreen.kt, between rows shown always). Draws their own [SettingsItem]
 * like its neighbours and opens [CareLinkActivity]. In a build without that window (upstream's own builds: it is
 * declared only in the `rus` manifest) nothing is drawn. Upstream's restricted mode does not reach this section (their
 * settings return early), and the window closes itself while locked.
 */
@Composable
fun RusCareLinkEntry(sharingColor: Color) {
    val context = LocalContext.current
    val intent = remember(context) { CareLinkActivity.intentIfDeclared(context) } ?: return
    SettingsItem(
        title = stringResource(R.string.rus_carelink_title),
        subtitle = stringResource(R.string.rus_carelink_entry_subtitle),
        icon = Icons.Default.CloudDownload,
        iconTint = sharingColor,
        position = CardPosition.MIDDLE,
        onClick = {
            try {
                context.startActivity(intent)
            } catch (t: Throwable) {
                CareLinkLog.w("CareLink window not opened (${t.javaClass.simpleName})")
            }
        },
    )
}
