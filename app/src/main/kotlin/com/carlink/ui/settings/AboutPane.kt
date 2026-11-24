package com.carlink.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * About (owner, 24 Nov: split out of Adapter and about): the app's version and the privacy policy ([PrivacyRow]), and where
 * lyrics come from and what of them the head unit keeps ([LyricsGroup]). Two columns, each group in the column it had
 * there: the app's on the left, lyrics on the right. The adapter's rows are under Adapter Config ([AdapterPane]).
 */
@Composable
internal fun AboutPane(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        PaneHeader(title = "About")
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                SettingsGroup {
                    SettingRow(title = "App version", icon = Icons.Default.Info) {
                        Text(rememberAppVersion() ?: "- - -", style = SettingsStyle.RowTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GroupDivider()
                    PrivacyRow()
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                LyricsGroup()
            }
        }
    }
}

/** The pane's words: plain functions, for its test. */
internal object AboutText {
    /** The list's line for About: the app's version ([version] null when the package manager can't say), then what else it holds. */
    fun summary(version: String?): String = version?.let { "Version $it · " }.orEmpty() + "Privacy and lyrics"
}

/** The published privacy policy (PRIVACY.md on main), the address Google Play lists too. */
private const val PRIVACY_POLICY_URL = "https://github.com/enigy/carlink/blob/main/PRIVACY.md"

/** The address as the row shows it, short enough to type on a phone. */
private const val PRIVACY_POLICY_SHOWN = "github.com/enigy/carlink/blob/main/PRIVACY.md"

/**
 * The privacy policy (owner, 10-30): its address, and Open, which hands it to a web browser. Many head
 * units have none; then the row says to open the address on a phone rather than doing nothing.
 */
@Composable
private fun PrivacyRow() {
    val context = LocalContext.current
    var noBrowser by remember { mutableStateOf(false) }
    val subtitle = if (noBrowser) "This head unit has no web browser. Open $PRIVACY_POLICY_SHOWN on your phone." else PRIVACY_POLICY_SHOWN
    SettingRow(title = "Privacy policy", subtitle = subtitle, icon = Icons.Default.PrivacyTip) {
        RowAction("Open", onClick = {
            noBrowser = !openWebPage(context, PRIVACY_POLICY_URL)
            logInfo("[UI_ACTION] Privacy policy: ${if (noBrowser) "no web browser" else "opened"}", tag = "UI")
        })
    }
}

/** Open [url] in a web browser; false when the head unit has none. */
private fun openWebPage(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/** The app's version as About and its summary show it, or null when the package manager can't say. */
@Composable
internal fun rememberAppVersion(): String? {
    val context = LocalContext.current
    return remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName // "2.00": the build number stays out of About (logs keep it)
        } catch (e: PackageManager.NameNotFoundException) {
            logWarn("[Settings] Failed to get package info: ${e.message}", tag = "UI")
            null
        }
    }
}
