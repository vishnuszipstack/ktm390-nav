package dev.ktmnav

import android.app.Application
import android.content.Context
import dev.ktmnav.bridge.BridgeEngine
import dev.ktmnav.diag.DiagCategory
import dev.ktmnav.diag.DiagLog
import dev.ktmnav.ktm.KtmDisplayAdapter
import dev.ktmnav.link.BikeDevice
import dev.ktmnav.link.BikeLink
import dev.ktmnav.link.Bluetooth
import dev.ktmnav.link.SimulatedTransport
import dev.ktmnav.link.Transport
import dev.ktmnav.nav.InstructionGate
import dev.ktmnav.settings.AppSettings
import dev.ktmnav.settings.SettingsRepository
import dev.ktmnav.sim.RouteSimulator
import dev.ktmnav.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KtmNavApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as KtmNavApp).container

/**
 * Process-wide objects. The notification listener, the foreground service and the UI all
 * share one link and one bridge, so there is never a second connection to the bike.
 */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = DiagLog()
    val settingsRepo = SettingsRepository(context)
    val bluetooth = Bluetooth(context)
    val adapter = KtmDisplayAdapter()
    val gate = InstructionGate()
    val link = BikeLink(scope, ::transportFor, log)
    val engine = BridgeEngine(link, adapter, gate, log)
    val simulator = RouteSimulator(scope) { engine.onNavEvent(it) }
    val updater = Updater(context)

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    init {
        link.onConnected = { engine.onLinkConnected() }
        scope.launch {
            settings.collect { s ->
                adapter.units = s.units
                adapter.drivesOnLeft = s.drivesOnLeft
                adapter.support = s.fieldSupport
                gate.staleAfterMs = s.staleTimeoutSec * 1000L
                log.verbose = s.verboseLogging
                link.reportIdle(bluetooth.idleState(s.bike))
            }
        }
        log.log(DiagCategory.SERVICE, "app started, version ${BuildConfig.VERSION_NAME}")
    }

    private fun transportFor(device: BikeDevice): Transport =
        if (device.isSimulator) SimulatedTransport()
        else bluetooth.transportFor(device, adapter.profile.rfcommUuid, adapter.profile.insecureSocket)

    fun refreshIdleState() {
        link.reportIdle(bluetooth.idleState(settings.value.bike))
    }
}
