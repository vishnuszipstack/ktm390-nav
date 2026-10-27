package dev.ktmnav

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.ktmnav.bridge.BridgeService
import dev.ktmnav.ui.AppRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }

        val c = container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                c.settings.collect { s ->
                    if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                // Wait for stored settings, not the defaults.
                val s = c.settingsRepo.settings.first()
                val bike = s.bike ?: return@launch
                if (s.autoConnect && (bike.isSimulator || c.bluetooth.hasConnectPermission()) && !BridgeService.running.value) {
                    runCatching { BridgeService.start(this@MainActivity) }
                }
            }
        }
    }
}
