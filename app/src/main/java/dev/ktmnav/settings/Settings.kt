package dev.ktmnav.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ktmnav.ktm.DisplayField
import dev.ktmnav.ktm.FieldSupport
import dev.ktmnav.link.BikeDevice
import dev.ktmnav.nav.DistanceUnits
import dev.ktmnav.nav.NavSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val acceptedDisclaimer: Boolean = false,
    val bike: BikeDevice? = null,
    val autoConnect: Boolean = true,
    val units: DistanceUnits = DistanceUnits.METRIC,
    val drivesOnLeft: Boolean = true,
    val staleTimeoutSec: Int = 30,
    val sources: Set<NavSource> = setOf(NavSource.GOOGLE_MAPS),
    val verboseLogging: Boolean = false,
    val keepScreenOn: Boolean = false,
    val fieldSupport: Map<DisplayField, FieldSupport> = emptyMap(),
    /** Experimental features stay off until the rider turns them on (FR-065). */
    val experimentalTestIcons: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISCLAIMER = booleanPreferencesKey("accepted_disclaimer")
        val BIKE_ADDRESS = stringPreferencesKey("bike_address")
        val BIKE_NAME = stringPreferencesKey("bike_name")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val UNITS = stringPreferencesKey("units")
        val LEFT = booleanPreferencesKey("drives_on_left")
        val STALE = intPreferencesKey("stale_timeout_sec")
        val SOURCES = stringPreferencesKey("sources")
        val VERBOSE = booleanPreferencesKey("verbose_logging")
        val SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val FIELDS = stringPreferencesKey("field_support")
        val EXPERIMENTAL = booleanPreferencesKey("experimental_test_icons")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    private fun read(p: Preferences): AppSettings {
        val address = p[Keys.BIKE_ADDRESS]
        return AppSettings(
            acceptedDisclaimer = p[Keys.DISCLAIMER] ?: false,
            bike = address?.let { BikeDevice(it, p[Keys.BIKE_NAME] ?: it) },
            autoConnect = p[Keys.AUTO_CONNECT] ?: true,
            units = p[Keys.UNITS]?.let { runCatching { DistanceUnits.valueOf(it) }.getOrNull() } ?: DistanceUnits.METRIC,
            drivesOnLeft = p[Keys.LEFT] ?: true,
            staleTimeoutSec = p[Keys.STALE] ?: 30,
            sources = p[Keys.SOURCES]?.split(',')
                ?.mapNotNull { runCatching { NavSource.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: setOf(NavSource.GOOGLE_MAPS),
            verboseLogging = p[Keys.VERBOSE] ?: false,
            keepScreenOn = p[Keys.SCREEN_ON] ?: false,
            fieldSupport = decodeFields(p[Keys.FIELDS]),
            experimentalTestIcons = p[Keys.EXPERIMENTAL] ?: false,
        )
    }

    suspend fun acceptDisclaimer() = context.dataStore.edit { it[Keys.DISCLAIMER] = true }

    suspend fun setBike(device: BikeDevice?) = context.dataStore.edit {
        if (device == null) {
            it.remove(Keys.BIKE_ADDRESS)
            it.remove(Keys.BIKE_NAME)
        } else {
            it[Keys.BIKE_ADDRESS] = device.address
            it[Keys.BIKE_NAME] = device.name
        }
    }

    suspend fun setAutoConnect(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_CONNECT] = v }
    suspend fun setUnits(v: DistanceUnits) = context.dataStore.edit { it[Keys.UNITS] = v.name }
    suspend fun setDrivesOnLeft(v: Boolean) = context.dataStore.edit { it[Keys.LEFT] = v }
    suspend fun setStaleTimeout(sec: Int) = context.dataStore.edit { it[Keys.STALE] = sec.coerceIn(10, 120) }
    suspend fun setVerbose(v: Boolean) = context.dataStore.edit { it[Keys.VERBOSE] = v }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[Keys.SCREEN_ON] = v }
    suspend fun setExperimental(v: Boolean) = context.dataStore.edit { it[Keys.EXPERIMENTAL] = v }

    suspend fun setSource(source: NavSource, enabled: Boolean) = context.dataStore.edit { p ->
        val current = read(p).sources
        val next = if (enabled) current + source else current - source
        p[Keys.SOURCES] = next.joinToString(",") { it.name }
    }

    suspend fun setFieldSupport(field: DisplayField, support: FieldSupport) = context.dataStore.edit { p ->
        val next = decodeFields(p[Keys.FIELDS]) + (field to support)
        p[Keys.FIELDS] = next.entries.joinToString(",") { "${it.key.name}=${it.value.name}" }
    }

    suspend fun resetAll() = context.dataStore.edit { it.clear() }

    private fun decodeFields(raw: String?): Map<DisplayField, FieldSupport> =
        raw.orEmpty().split(',').mapNotNull { pair ->
            val (k, v) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val f = runCatching { DisplayField.valueOf(k) }.getOrNull() ?: return@mapNotNull null
            val s = runCatching { FieldSupport.valueOf(v) }.getOrNull() ?: return@mapNotNull null
            f to s
        }.toMap()
}
