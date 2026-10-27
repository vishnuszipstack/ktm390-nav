package dev.ktmnav.source

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import dev.ktmnav.bridge.BridgeService
import dev.ktmnav.container
import dev.ktmnav.diag.DiagCategory
import dev.ktmnav.nav.NavEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads navigation notifications (FR-017) from the enabled sources only. Every other app's
 * notifications are dropped on the first line and never logged or forwarded (FR-060).
 */
class NavListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        _connected.value = true
        container.log.log(DiagCategory.PARSER, "notification access active")
        // Pick up a route that was already running when access was granted.
        runCatching { activeNotifications }.getOrNull()?.forEach(::handle)
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        container.log.log(DiagCategory.PARSER, "notification access disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val source = NavPackages.sourceOf(sbn.packageName) ?: return
        if (source !in container.settings.value.sources) return
        if (!isNavigation(sbn)) return
        container.engine.onNavEvent(NavEvent.Ended(source, System.currentTimeMillis()))
    }

    private fun handle(sbn: StatusBarNotification) {
        val source = NavPackages.sourceOf(sbn.packageName) ?: return
        if (source !in container.settings.value.sources) return
        _lastSeenNavAppMs.value = System.currentTimeMillis()
        if (!isNavigation(sbn)) return
        maybeStartBridge()

        val raw = toRaw(sbn)
        container.log.logSensitive(
            DiagCategory.PARSER,
            "${source.label} notification",
            "title=\"${raw.title}\" text=\"${raw.text}\" sub=\"${raw.subText}\" big=\"${raw.bigText}\" lines=${raw.lines}",
        )
        if (container.settings.value.verboseLogging) captureFixture(raw)
        container.engine.onNavEvent(NavNotificationParser.parse(raw))
    }

    /** Guidance started with the app closed: bring the bike link up if the rider asked for that. */
    private fun maybeStartBridge() {
        val s = container.settings.value
        if (!s.autoConnect || s.bike == null || BridgeService.running.value) return
        runCatching { BridgeService.start(this) }
            .onFailure { container.log.log(DiagCategory.SERVICE, "could not start the bridge from the background: ${it.message}") }
    }

    /** Turn-by-turn notifications are ongoing and (on current Maps) in the navigation category. */
    private fun isNavigation(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return n.category == Notification.CATEGORY_NAVIGATION || sbn.isOngoing
    }

    private fun toRaw(sbn: StatusBarNotification): RawNavNotification {
        val e: Bundle = sbn.notification.extras
        fun str(key: String) = e.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
        return RawNavNotification(
            packageName = sbn.packageName,
            title = str(Notification.EXTRA_TITLE) ?: str(Notification.EXTRA_TITLE_BIG),
            text = str(Notification.EXTRA_TEXT),
            subText = str(Notification.EXTRA_SUB_TEXT),
            bigText = str(Notification.EXTRA_BIG_TEXT),
            infoText = str(Notification.EXTRA_INFO_TEXT),
            lines = lines,
            ongoing = sbn.isOngoing,
            postedAtMs = System.currentTimeMillis(),
        )
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _lastSeenNavAppMs = MutableStateFlow<Long?>(null)
        /** Any notification from an enabled navigation app, navigation or not. */
        val lastSeenNavAppMs: StateFlow<Long?> = _lastSeenNavAppMs.asStateFlow()

        private val _fixtures = MutableStateFlow<List<RawNavNotification>>(emptyList())
        /** Raw notifications captured with verbose logging on, for parser fixes. */
        val fixtures: StateFlow<List<RawNavNotification>> = _fixtures.asStateFlow()

        private fun captureFixture(raw: RawNavNotification) {
            val list = _fixtures.value
            if (list.lastOrNull()?.copy(postedAtMs = 0) == raw.copy(postedAtMs = 0)) return
            _fixtures.value = (list + raw).takeLast(50)
        }

        fun clearFixtures() {
            _fixtures.value = emptyList()
        }

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(context: Context): android.content.Intent =
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NavListenerService::class.java).flattenToString(),
                )
            } else {
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }
}
