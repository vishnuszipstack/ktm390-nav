package dev.ktmnav.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.ktmnav.MainActivity
import dev.ktmnav.R
import dev.ktmnav.container
import dev.ktmnav.diag.DiagCategory
import dev.ktmnav.link.LinkState
import dev.ktmnav.nav.Units
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the bike link and the stale-data timer alive with the screen off (FR-048, FR-059).
 * Foreground type "connectedDevice": it exists only to hold the Bluetooth link.
 */
class BridgeService : LifecycleService() {

    private var unwatch: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val c = container
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting", null),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }
        foreground.onFailure { c.log.log(DiagCategory.SERVICE, "could not run in the foreground: ${it.message}") }
        _running.value = true
        c.log.log(DiagCategory.SERVICE, "bridge service started")

        unwatch = c.bluetooth.watch(
            onChange = { c.refreshIdleState() },
            onDeviceConnected = { address ->
                if (address == c.settings.value.bike?.address) {
                    c.log.log(DiagCategory.LINK, "bike is back in range")
                    c.link.bikeAppeared()
                }
            },
        )

        lifecycleScope.launch {
            while (isActive) {
                c.engine.tick()
                delay(TICK_MS)
            }
        }
        lifecycleScope.launch {
            combine(c.link.state, c.engine.status) { link, status -> link to status }
                .distinctUntilChanged()
                .collect { (link, status) ->
                    val turn = status.onDisplay?.let { i ->
                        val d = i.distanceToManeuverMeters?.let { Units.format(it, c.adapter.units).toString() }
                        listOfNotNull(i.maneuver.name.lowercase().replace('_', ' '), d, i.roadName).joinToString(" · ")
                    }
                    notify(link.label, turn)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val c = container
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    c.engine.clearDisplay("bridge stopped")
                    delay(300) // let the restore frame go out
                    c.link.disconnect()
                    stopSelf()
                }
            }
            else -> {
                val bike = c.settings.value.bike
                if (bike == null) {
                    c.log.log(DiagCategory.SERVICE, "no bike selected")
                } else {
                    lifecycleScope.launch { c.link.connect(bike) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unwatch?.invoke()
        _running.value = false
        container.log.log(DiagCategory.SERVICE, "bridge service stopped")
        super.onDestroy()
    }

    private fun notify(state: String, turn: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(state, turn)) }
    }

    private fun buildNotification(state: String, turn: String?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setContentTitle(turn ?: "Ride Nav bridge")
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 2_000L
        private const val ACTION_STOP = "dev.ktmnav.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            if (_running.value) context.startService(Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Bike connection", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while the app is connected to the bike"
                    },
                )
            }
        }
    }
}

/** True while the link is up or being retried, for UI buttons. */
val LinkState.isActive: Boolean
    get() = this is LinkState.Connected || this is LinkState.Connecting || this is LinkState.Reconnecting
