package dev.ktmnav.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.bridge.BridgeService
import dev.ktmnav.bridge.isActive
import dev.ktmnav.container
import dev.ktmnav.link.LinkState
import dev.ktmnav.nav.InstructionGate
import dev.ktmnav.nav.Units
import dev.ktmnav.source.NavListenerService
import dev.ktmnav.source.NavPackages

/** The glanceable screen: next turn, trip progress, and whether the bike is getting it. */
@Composable
fun RideScreen(go: (Tab) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val status by c.engine.status.collectAsStateWithLifecycle()
    val link by c.link.state.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val listening by NavListenerService.connected.collectAsStateWithLifecycle()
    val now = rememberNow()
    val hasAccess = NavListenerService.hasAccess(context)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val setupMissing = buildList {
            if (!hasAccess) add("Allow notification access so the app can read Google Maps guidance")
            if (!c.bluetooth.hasConnectPermission()) add("Allow Nearby devices so the app can talk to the bike")
            if (settings.bike == null) add("Choose your bike (or the simulator) on the Bike tab")
        }
        if (setupMissing.isNotEmpty()) {
            SectionCard("Finish setup (while parked)") {
                setupMissing.forEach { CheckRow(Check.WARN, it) }
                Button(onClick = { go(Tab.BIKE) }) { Text("Open setup") }
            }
        }

        // Next turn: what the parser saw, and whether it is on the TFT.
        val shown = status.onDisplay
        val latest = shown ?: status.latest
        val lastAt = status.lastNotificationAtMs
        val silentFor = lastAt?.let { now - it }
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TurnArrow(latest?.maneuver, latest?.roundaboutExit, 120.dp, color = if (shown != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val dist = latest?.distanceToManeuverMeters?.let { Units.format(it, settings.units) }
                    Text(
                        dist?.toString() ?: if (latest == null) "No guidance" else "--",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        latest?.let { maneuverLabel(it.maneuver, it.roundaboutExit) } ?: "Start a route in Google Maps",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    latest?.roadName?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val remaining = listOfNotNull(
                latest?.remainingDistanceMeters?.let { Units.format(it, settings.units).toString() + " to go" },
                latest?.remainingMinutes?.let { "$it min" },
                latest?.etaEpochMs?.let { "arrive ${formatClock(it)}" },
            )
            if (remaining.isNotEmpty()) Text(remaining.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            when {
                latest == null -> Unit
                shown != null && link is LinkState.Connected -> Pill("On the bike display", NavColors.Ok)
                shown != null -> Pill("Not on the bike: ${link.label.lowercase()}", NavColors.Warning)
                latest.confidence < InstructionGate.MIN_CONFIDENCE || latest.maneuver == dev.ktmnav.nav.Maneuver.UNKNOWN ->
                    Pill("Not sent: direction unclear", NavColors.Warning)
                else -> Pill("Cleared from the bike", NavColors.Warning)
            }
            if (status.navigating && silentFor != null && silentFor > settings.staleTimeoutSec * 1000L) {
                CheckRow(Check.WARN, "No navigation updates for ${silentFor / 1000}s", "Is Google Maps still navigating? The bike display was cleared.")
            }
            if (hasAccess && !listening) {
                CheckRow(Check.WARN, "Notification access is on but not connected yet", "Toggle access off and on in Android settings if this stays.")
            }
        }

        SectionCard("Bike") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(settings.bike?.name ?: "No bike chosen", style = MaterialTheme.typography.titleMedium)
                    Text(link.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinkPill(link)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (link.isActive) {
                    OutlinedButton(onClick = { BridgeService.stop(context) }, modifier = Modifier.height(52.dp)) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = { BridgeService.start(context) },
                        enabled = settings.bike != null,
                        modifier = Modifier.height(52.dp),
                    ) { Text("Connect") }
                }
                OutlinedButton(onClick = { openMaps(context) }, modifier = Modifier.height(52.dp)) { Text("Open Google Maps") }
            }
        }

        UpdateCard()

        Text(
            "Set up and test only while parked. The phone is optional while riding: guidance keeps " +
                "flowing to the bike with the screen off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LinkPill(link: LinkState) {
    when (link) {
        is LinkState.Connected -> Pill(if (link.lastWriteOkMs != null) "Display link" else "Connected", NavColors.Ok)
        is LinkState.Connecting, is LinkState.Reconnecting -> Pill("Connecting", NavColors.Warning)
        is LinkState.Error -> Pill("Error", NavColors.Danger)
        else -> Pill("Offline", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openMaps(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(NavPackages.GOOGLE_MAPS)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
