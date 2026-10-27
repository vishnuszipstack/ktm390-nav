package dev.ktmnav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.container
import dev.ktmnav.update.UpdateState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Check GitHub for a newer build and install it over this one. One tap checks; when a
 * newer release exists the same button downloads and installs it.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val update by c.updater.state.collectAsStateWithLifecycle()

    SectionCard("App update") {
        Text("Installed: version ${c.updater.currentVersionName}")
        when (val st = update) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text("Checking GitHub…")
            is UpdateState.UpToDate -> Text("You're on the latest build.", color = MaterialTheme.colorScheme.primary)
            is UpdateState.Available -> Text(
                "Update available: ${st.release.name} (${"%.1f".format(st.release.sizeBytes / 1_048_576.0)} MB)",
                fontWeight = FontWeight.SemiBold,
            )
            is UpdateState.Downloading -> {
                Text("Downloading ${(st.progress * 100).roundToInt()}%")
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.NeedsInstallPermission -> Text(
                "Allow this app to install updates (\"Allow from this source\"), come back, and tap Install.",
                color = NavColors.Warning,
            )
            UpdateState.Installing -> Text("Installing… confirm on the system prompt if asked.")
            is UpdateState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tall = Modifier.height(52.dp)
            when (val st = update) {
                is UpdateState.Available -> Button(onClick = { scope.launch { c.updater.downloadAndInstall(st.release) } }, modifier = tall) {
                    Text("Update now")
                }
                is UpdateState.NeedsInstallPermission -> {
                    Button(onClick = { context.startActivity(c.updater.installPermissionIntent()) }, modifier = tall) { Text("Allow installs") }
                    OutlinedButton(onClick = { scope.launch { c.updater.downloadAndInstall(st.release) } }, modifier = tall) { Text("Install") }
                }
                else -> {
                    val busy = st is UpdateState.Checking || st is UpdateState.Downloading || st is UpdateState.Installing
                    Button(onClick = { scope.launch { c.updater.check() } }, enabled = !busy, modifier = tall) { Text("Check for updates") }
                }
            }
        }
        Text(
            "Updates come from the latest GitHub release and install over this version, keeping your settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
