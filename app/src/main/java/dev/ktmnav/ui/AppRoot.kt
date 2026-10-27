package dev.ktmnav.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ktmnav.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    RIDE("Ride", Icons.Filled.Navigation),
    BIKE("Bike", Icons.Filled.TwoWheeler),
    TEST("Test TFT", Icons.Filled.Tv),
    DIAG("Diagnostics", Icons.Filled.BugReport),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Wall clock that recomposes once a second, for "x s ago" labels. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun AppRoot() {
    val c = LocalContext.current.container
    // Null until DataStore has loaded, so the disclaimer doesn't flash for returning riders.
    val stored by c.settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableStateOf(Tab.RIDE) }
    val scope = rememberCoroutineScope()

    NavTheme(dark = isSystemInDarkTheme()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val go: (Tab) -> Unit = { tab = it }
                when (tab) {
                    Tab.RIDE -> RideScreen(go)
                    Tab.BIKE -> BikeScreen()
                    Tab.TEST -> TestScreen()
                    Tab.DIAG -> DiagnosticsScreen()
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }

        if (stored?.acceptedDisclaimer == false) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Before you ride") },
                text = {
                    Text(
                        "This is an independent app. It is not made by, affiliated with or endorsed by KTM or Google.\n\n" +
                            "It reads Google Maps' navigation notification and sends the turn to your bike's display " +
                            "over the MY RIDE Bluetooth link. That link is not publicly documented, so check every " +
                            "message on the Test TFT screen with the bike parked before relying on it.\n\n" +
                            "Never set up or operate the phone while riding. Road signs and your own judgement always come first.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { scope.launch { c.settingsRepo.acceptDisclaimer() } }) { Text("I understand") }
                },
            )
        }
    }
}
