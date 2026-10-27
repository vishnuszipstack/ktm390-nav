package dev.ktmnav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.container
import dev.ktmnav.ktm.DashboardView
import dev.ktmnav.ktm.DisplayField
import dev.ktmnav.ktm.FieldSupport
import dev.ktmnav.ktm.TurnIcon
import dev.ktmnav.ktm.UiContext
import dev.ktmnav.link.LinkState
import dev.ktmnav.sim.RouteSimulator
import kotlinx.coroutines.launch
import org.json.JSONObject

private val CORE_TESTS = listOf(
    "Left" to TurnIcon.QUITE_LEFT,
    "Right" to TurnIcon.QUITE_RIGHT,
    "Straight" to TurnIcon.GO_STRAIGHT,
    "Slight left" to TurnIcon.LIGHT_LEFT,
    "Sharp right" to TurnIcon.HEAVY_RIGHT,
    "U-turn" to TurnIcon.UTURN_RIGHT,
    "Roundabout 2nd exit" to TurnIcon.RAB_SECT_8_LH,
    "Arrive" to TurnIcon.END,
)

/** Stationary TFT tests (FR-032) and the route simulator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestScreen() {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val link by c.link.state.collectAsStateWithLifecycle()
    val status by c.engine.status.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val running by c.simulator.running.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("Ride Nav test") }

    fun sendTurn(label: String, icon: TurnIcon) {
        c.engine.sendTest(
            DashboardView(
                context = UiContext.GUIDANCE,
                turnIcon = icon,
                turnDist = "350",
                turnDistUnit = "m",
                turnRoad = "Test Road",
                dist2Target = "12 km",
                eta = "14:35",
            ),
            label,
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(null) {
            CheckRow(Check.WARN, "Bike parked, stand down", "Only run these tests stationary. Watch the TFT after each one.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(link.label, Modifier.weight(1f))
                LinkPill(link)
            }
            if (link !is LinkState.Connected) {
                Text("Connect on the Bike tab first, or choose the simulator.", color = NavColors.Warning)
            }
        }

        SectionCard("Text message") {
            OutlinedTextField(value = message, onValueChange = { message = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { c.engine.sendTest(c.adapter.textView(message), "text") }) { Text("Send text") }
        }

        SectionCard("Turn instructions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CORE_TESTS.forEach { (label, icon) -> OutlinedButton(onClick = { sendTurn(label, icon) }) { Text(label) } }
            }
            if (settings.experimentalTestIcons) {
                Text("Every icon (experimental)", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TurnIcon.entries.forEach { icon ->
                        OutlinedButton(onClick = { sendTurn(icon.name, icon) }) { Text(icon.name, fontSize = 11.sp) }
                    }
                }
            }
            Button(onClick = { c.engine.clearDisplay("test") }) { Text("Return to bike screen") }
        }

        SectionCard("What did the TFT show?") {
            Text(
                "Mark each field after a test. Fields marked “Not shown” are never sent during navigation; " +
                    "they stay on the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DisplayField.entries.forEach { f ->
                val current = settings.fieldSupport[f] ?: FieldSupport.UNKNOWN
                Column {
                    Text(f.label, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(FieldSupport.CONFIRMED to "Shown", FieldSupport.NOT_SHOWN to "Not shown", FieldSupport.UNKNOWN to "Not tested").forEach { (s, label) ->
                            FilterChip(
                                selected = current == s,
                                onClick = { scope.launch { c.settingsRepo.setFieldSupport(f, s) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        SectionCard("Last message sent") {
            TftPreview(status.lastSentJson)
            status.lastSentAtMs?.let { Text("at ${formatTime(it)} · message #${c.adapter.sequence}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        SectionCard("Route simulator") {
            Text(
                "Plays a scripted Google Maps route through the real parser and bridge. Works with the bike " +
                    "or the simulated TFT.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RouteSimulator.SCRIPTS.forEach { (name, steps) ->
                OutlinedButton(onClick = { c.simulator.play(name, steps) }, enabled = running == null) { Text(name) }
            }
            if (running != null) {
                Text("Playing: $running", color = MaterialTheme.colorScheme.primary)
                Button(onClick = { c.simulator.stop() }) { Text("Stop") }
            }
        }
    }
}

/** A rough drawing of what the TFT was told to show, decoded from the JSON actually sent. */
@Composable
fun TftPreview(json: String?) {
    val parsed = remember(json) { json?.let { runCatching { JSONObject(it) }.getOrNull() } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(NavColors.Tft, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (parsed == null) {
            Text("Nothing sent yet", color = NavColors.TftText.copy(alpha = 0.6f))
            return@Column
        }
        val ui = parsed.optJSONObject("UpdateUI")
        fun field(name: String, key: String = "Text"): String? {
            val o = ui?.optJSONObject(name) ?: return null
            return if (o.optString("Visibility") == "full") o.optString(key).takeIf { it.isNotEmpty() } else null
        }
        val context = parsed.optString("UiContext")
        Text("$context · ${parsed.optString("MsgId")}", color = NavColors.TftText.copy(alpha = 0.6f), fontSize = 12.sp)
        if (context == "guidance") {
            Text(field("TurnIcon", "Image") ?: "(no icon)", color = NavColors.Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(listOfNotNull(field("TurnDist"), field("TurnDistUnit")).joinToString(" ").ifEmpty { "–" }, color = NavColors.TftText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            field("TurnRoad")?.let { Text(it, color = NavColors.TftText, fontSize = 18.sp) }
            val bottom = listOfNotNull(field("Dist2Target"), field("ETA")).joinToString("   ")
            if (bottom.isNotEmpty()) Text(bottom, color = NavColors.TftText.copy(alpha = 0.8f))
        } else {
            Text(field("NotificationText") ?: "(bike's own screen)", color = NavColors.TftText, fontSize = 18.sp)
        }
        Text(json ?: "", color = NavColors.TftText.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
    }
}
