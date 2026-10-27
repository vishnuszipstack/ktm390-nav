package dev.ktmnav.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.bridge.BridgeService
import dev.ktmnav.bridge.isActive
import dev.ktmnav.container
import dev.ktmnav.ktm.DisplayField
import dev.ktmnav.ktm.FieldSupport
import dev.ktmnav.link.BikeDevice
import dev.ktmnav.link.Bluetooth
import dev.ktmnav.link.LinkState
import dev.ktmnav.source.NavListenerService
import kotlinx.coroutines.launch

/** Setup wizard and compatibility report (FR-001…FR-008), all meant for a parked bike. */
@Composable
fun BikeScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.collectAsStateWithLifecycle()
    val link by c.link.state.collectAsStateWithLifecycle()
    val status by c.engine.status.collectAsStateWithLifecycle()

    // Re-read permission and Bluetooth state whenever we come back from system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        c.refreshIdleState()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        c.refreshIdleState()
    }

    // Reading `refresh` here re-runs the checks below after each permission or settings change.
    if (refresh < 0) return
    val hasBt = c.bluetooth.hasConnectPermission()
    val btOn = c.bluetooth.isEnabled()
    val hasAccess = NavListenerService.hasAccess(context)
    val notifPermission = Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val bonded = if (hasBt) c.bluetooth.bondedDevices() else emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("1 · Permissions") {
            CheckRow(
                if (hasAccess) Check.OK else Check.FAIL,
                "Notification access",
                "Lets the app read the navigation notification from the apps you enable in Settings. " +
                    "Other apps' notifications are ignored and never sent to the bike.",
            ) {
                if (!hasAccess) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(NavListenerService.settingsIntent(context)) }
                            .onFailure { context.startActivity(NavListenerService.fallbackSettingsIntent()) }
                    }) { Text("Allow") }
                }
            }
            CheckRow(
                if (hasBt) Check.OK else Check.FAIL,
                "Nearby devices (Bluetooth)",
                "Needed to open the link to the bike.",
            ) {
                if (!hasBt && Build.VERSION.SDK_INT >= 31) {
                    TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }) { Text("Allow") }
                }
            }
            CheckRow(
                if (notifPermission) Check.OK else Check.WARN,
                "Show notifications",
                "For the connection status notification that keeps the link alive with the screen off.",
            ) {
                if (!notifPermission && Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow") }
                }
            }
        }

        SectionCard("2 · Pair the bike") {
            CheckRow(
                when {
                    !c.bluetooth.isAvailable -> Check.FAIL
                    btOn -> Check.OK
                    else -> Check.FAIL
                },
                if (btOn) "Bluetooth is on" else "Bluetooth is off",
            ) {
                if (!btOn && hasBt) {
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Settings") }
                }
            }
            Text(
                "On the bike: ignition on, open the TFT menu and switch on the phone / MY RIDE connection " +
                    "(the KTM MY RIDE connectivity unit has to be fitted). Then pair the bike in Android's Bluetooth " +
                    "settings and come back here. Pairing alone doesn't prove the navigation link works: " +
                    "use Test TFT for that.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Open Bluetooth settings")
            }
        }

        SectionCard("3 · Choose the bike") {
            if (bonded.isEmpty() && hasBt) {
                Text("No paired devices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (bonded + BikeDevice.SIMULATOR).forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { c.settingsRepo.setBike(d) } }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.bike?.address == d.address, onClick = { scope.launch { c.settingsRepo.setBike(d) } })
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (Bluetooth.looksLikeBike(d.name)) "  ★" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (d.isSimulator) "No bike needed: frames are decoded on the phone" else d.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("4 · Connect") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(link.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                LinkPill(link)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { BridgeService.start(context) }, enabled = settings.bike != null) {
                    Text(if (link is LinkState.Reconnecting || link is LinkState.Error) "Retry now" else "Connect")
                }
                if (link.isActive) OutlinedButton(onClick = { BridgeService.stop(context) }) { Text("Disconnect") }
            }
        }

        // FR-007: each stage reported separately.
        SectionCard("Compatibility report") {
            val bike = settings.bike
            CheckRow(if (bike != null) Check.OK else Check.UNKNOWN, "Bike chosen", bike?.name ?: "none")
            CheckRow(
                when {
                    bike == null -> Check.UNKNOWN
                    bike.isSimulator || bonded.any { it.address == bike.address } -> Check.OK
                    else -> Check.FAIL
                },
                "Bluetooth pairing",
            )
            val conn = link as? LinkState.Connected
            CheckRow(if (conn != null) Check.OK else Check.UNKNOWN, "Navigation link (RFCOMM) connected", conn?.let { "since ${formatTime(it.sinceMs)}" })
            CheckRow(
                if (conn?.lastWriteOkMs != null) Check.OK else Check.UNKNOWN,
                "Message written to the bike",
                status.lastSentAtMs?.let { "last at ${formatTime(it)}" } ?: "send one from Test TFT",
            )
            fun confirmed(f: DisplayField) = when (settings.fieldSupport[f]) {
                FieldSupport.CONFIRMED -> Check.OK
                FieldSupport.NOT_SHOWN -> Check.FAIL
                else -> Check.UNKNOWN
            }
            CheckRow(confirmed(DisplayField.NOTIFICATION_TEXT), "Test message shown on the TFT", "confirmed by you on Test TFT")
            CheckRow(confirmed(DisplayField.TURN_ICON), "Turn instruction shown on the TFT", "confirmed by you on Test TFT")
            CheckRow(
                Check.FAIL,
                "Mini-map / custom graphics",
                "Unsupported: the known MY RIDE protocol only takes fixed turn icons and text. The phone keeps the map.",
            )
            val overall = when {
                settings.fieldSupport[DisplayField.TURN_ICON] == FieldSupport.CONFIRMED &&
                    settings.fieldSupport[DisplayField.TURN_DISTANCE] == FieldSupport.CONFIRMED -> "Confirmed (turn-by-turn)"
                settings.fieldSupport.values.any { it == FieldSupport.CONFIRMED } -> "Partial"
                settings.fieldSupport[DisplayField.TURN_ICON] == FieldSupport.NOT_SHOWN -> "Unsupported on this bike"
                else -> "Experimental (not yet tested on your bike)"
            }
            Text("Overall: $overall", style = MaterialTheme.typography.titleMedium)
            Text(c.adapter.profile.provenance, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
