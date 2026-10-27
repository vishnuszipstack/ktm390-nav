package dev.ktmnav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.bridge.BridgeService
import dev.ktmnav.container
import dev.ktmnav.nav.DistanceUnits
import dev.ktmnav.nav.NavSource
import dev.ktmnav.update.UpdateState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.collectAsStateWithLifecycle()
    val update by c.updater.state.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Navigation sources") {
            Text(
                "Google Maps is the tested source. Others use the same text parser and are untested.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(NavSource.GOOGLE_MAPS, NavSource.OSMAND, NavSource.WAZE).forEach { src ->
                ToggleRow(src.label, null, src in s.sources) { on -> scope.launch { c.settingsRepo.setSource(src, on) } }
            }
            Text(
                "Google Maps can change its notification wording in any update. If turns stop showing, " +
                    "turn on verbose logging, ride a short route, and share the captured samples.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Display") {
            Text("Distance units", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.units == DistanceUnits.METRIC, onClick = { scope.launch { c.settingsRepo.setUnits(DistanceUnits.METRIC) } }, label = { Text("Kilometres") })
                FilterChip(selected = s.units == DistanceUnits.IMPERIAL, onClick = { scope.launch { c.settingsRepo.setUnits(DistanceUnits.IMPERIAL) } }, label = { Text("Miles") })
            }
            ToggleRow(
                "Traffic drives on the left",
                "Sets roundabout direction and U-turn side (on for India).",
                s.drivesOnLeft,
            ) { on -> scope.launch { c.settingsRepo.setDrivesOnLeft(on) } }

            var stale by remember(s.staleTimeoutSec) { mutableFloatStateOf(s.staleTimeoutSec.toFloat()) }
            Text("Clear the bike display after ${stale.roundToInt()} s without updates", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = stale,
                onValueChange = { stale = it },
                onValueChangeFinished = { scope.launch { c.settingsRepo.setStaleTimeout(stale.roundToInt()) } },
                valueRange = 10f..120f,
                steps = 21,
            )
        }

        SectionCard("Connection") {
            ToggleRow(
                "Connect automatically",
                "Brings the bike link up when the app opens or when Google Maps starts guidance.",
                s.autoConnect,
            ) { on -> scope.launch { c.settingsRepo.setAutoConnect(on) } }
            ToggleRow("Keep phone screen on", "Only while this app is open. Uses more battery and heat.", s.keepScreenOn) { on ->
                scope.launch { c.settingsRepo.setKeepScreenOn(on) }
            }
        }

        SectionCard("Troubleshooting") {
            ToggleRow(
                "Verbose logging",
                "Records navigation notification text in the local log and keeps samples for parser fixes. Off by default.",
                s.verboseLogging,
            ) { on -> scope.launch { c.settingsRepo.setVerbose(on) } }
            ToggleRow(
                "Experimental: test every TFT icon",
                "Adds all 29 known icon names to Test TFT.",
                s.experimentalTestIcons,
            ) { on -> scope.launch { c.settingsRepo.setExperimental(on) } }
        }

        SectionCard("Updates") {
            Text("Installed: version ${c.updater.currentVersionName}")
            when (val st = update) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Text("Checking GitHub…")
                is UpdateState.UpToDate -> Text("You're on the latest build.", color = MaterialTheme.colorScheme.primary)
                is UpdateState.Available -> Text("Update available: ${st.release.name}", fontWeight = FontWeight.SemiBold)
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
                when (val st = update) {
                    is UpdateState.Available -> Button(onClick = { scope.launch { c.updater.downloadAndInstall(st.release) } }) { Text("Download and install") }
                    is UpdateState.NeedsInstallPermission -> {
                        Button(onClick = { context.startActivity(c.updater.installPermissionIntent()) }) { Text("Allow installs") }
                        OutlinedButton(onClick = { scope.launch { c.updater.downloadAndInstall(st.release) } }) { Text("Install") }
                    }
                    else -> {
                        val busy = st is UpdateState.Checking || st is UpdateState.Downloading || st is UpdateState.Installing
                        Button(onClick = { scope.launch { c.updater.check() } }, enabled = !busy) { Text("Check for updates") }
                    }
                }
            }
        }

        SectionCard("Your data") {
            Text(
                "Everything stays on this phone: no account, no uploads. Resetting forgets the bike, field checks and settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmReset = true }) { Text("Delete all app data") }
        }

        Text(
            "Independent project, not affiliated with KTM or Google.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Delete all app data?") },
            text = { Text("This disconnects the bike and clears every setting and log on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    BridgeService.stop(context)
                    c.log.clear()
                    scope.launch { c.settingsRepo.resetAll() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
