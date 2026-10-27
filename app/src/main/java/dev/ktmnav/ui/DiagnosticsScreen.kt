package dev.ktmnav.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.AppContainer
import dev.ktmnav.BuildConfig
import dev.ktmnav.container
import dev.ktmnav.diag.DiagLog
import dev.ktmnav.link.LinkState
import dev.ktmnav.source.NavListenerService
import dev.ktmnav.source.RawNavNotification

/** Troubleshooting while stopped (10.5): which stage is failing, and a report to share. */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val c = context.container
    val status by c.engine.status.collectAsStateWithLifecycle()
    val link by c.link.state.collectAsStateWithLifecycle()
    val entries by c.log.entries.collectAsStateWithLifecycle()
    val lastSeenNavApp by NavListenerService.lastSeenNavAppMs.collectAsStateWithLifecycle()
    val listening by NavListenerService.connected.collectAsStateWithLifecycle()
    val fixtures by NavListenerService.fixtures.collectAsStateWithLifecycle()
    val now = rememberNow()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Stages") {
            val access = NavListenerService.hasAccess(context)
            CheckRow(if (access && listening) Check.OK else if (access) Check.WARN else Check.FAIL, "Notification access", if (listening) "listener connected" else "listener not connected")
            CheckRow(
                if (lastSeenNavApp != null) Check.OK else Check.UNKNOWN,
                "Navigation app notifications",
                "last seen ${ago(lastSeenNavApp, now)}. If never: start a route in Google Maps and check its notifications are allowed.",
            )
            val latest = status.latest
            CheckRow(
                when {
                    latest == null -> Check.UNKNOWN
                    latest.confidence >= 0.6 -> Check.OK
                    else -> Check.WARN
                },
                "Last parsed instruction",
                latest?.let { "${it.maneuver} · ${it.distanceToManeuverMeters?.toInt() ?: "?"} m · confidence ${"%.2f".format(it.confidence)} · ${ago(it.receivedAtMs, now)}" }
                    ?: "none yet",
            )
            CheckRow(Check.UNKNOWN, "Bridge decision", status.lastDecision ?: "none yet")
            CheckRow(
                if (c.bluetooth.isEnabled()) Check.OK else Check.FAIL,
                "Bluetooth adapter",
                when {
                    !c.bluetooth.isAvailable -> "not available on this phone"
                    !c.bluetooth.hasConnectPermission() -> "permission missing"
                    c.bluetooth.isEnabled() -> "on"
                    else -> "off"
                },
            )
            CheckRow(if (link is LinkState.Connected) Check.OK else Check.UNKNOWN, "Bike link", link.label)
            CheckRow(
                if (status.lastSentAtMs != null) Check.OK else Check.UNKNOWN,
                "Last TFT message",
                status.lastSentAtMs?.let { "${ago(it, now)} (the bike doesn't confirm what it drew)" } ?: "none sent",
            )
            val ignoring = isIgnoringBatteryOptimizations(context)
            CheckRow(
                if (ignoring) Check.OK else Check.WARN,
                "Battery optimisation",
                if (ignoring) "unrestricted" else "Some phones stop background apps. Set this app to Unrestricted if guidance stops with the screen off.",
            ) {
                if (!ignoring) TextButton(onClick = { openBatterySettings(context) }) { Text("Fix") }
            }
        }

        SectionCard("Share") {
            Text(
                "Reports stay on the phone until you share them. Coordinates are removed, and notification text " +
                    "is only included when verbose logging is on (Settings).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { share(context, "KTM Nav diagnostics", report(c, link)) }) { Text("Share report") }
                OutlinedButton(onClick = { c.log.clear() }) { Text("Clear log") }
            }
            if (fixtures.isNotEmpty()) {
                Text("${fixtures.size} navigation notifications captured for parser fixes.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { share(context, "KTM Nav notification samples", fixturesText(fixtures)) }) { Text("Share samples") }
                    TextButton(onClick = { NavListenerService.clearFixtures() }) { Text("Discard") }
                }
            }
        }

        SectionCard("Log (${entries.size})") {
            entries.takeLast(120).asReversed().forEach {
                Text(
                    "${formatTime(it.atMs)} ${it.category} ${it.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun report(c: AppContainer, link: LinkState): String {
    val s = c.settings.value
    val header = buildString {
        appendLine("KTM Nav ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Protocol: ${c.adapter.profile.name}")
        appendLine("Link: ${link.label}")
        appendLine("Sources: ${s.sources.joinToString { it.label }}, stale after ${s.staleTimeoutSec}s, units ${s.units}")
        appendLine("Field checks: ${s.fieldSupport.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }}")
        appendLine()
    }
    return DiagLog.redactCoordinates(header + c.log.format())
}

private fun fixturesText(list: List<RawNavNotification>): String = list.joinToString("\n\n") {
    "pkg=${it.packageName}\ntitle=${it.title}\ntext=${it.text}\nsub=${it.subText}\nbig=${it.bigText}\ninfo=${it.infoText}\nlines=${it.lines}"
}

private fun share(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, subject))
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun openBatterySettings(context: Context) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(details) }
}
