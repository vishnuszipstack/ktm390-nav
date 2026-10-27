package dev.ktmnav.nav

import java.util.Locale
import kotlin.math.roundToInt

enum class DistanceUnits { METRIC, IMPERIAL }

/** A distance split into the number and unit the TFT shows in separate fields. */
data class FormattedDistance(val value: String, val unit: String) {
    override fun toString() = "$value $unit"
}

object Units {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    /**
     * Rounded to what a rider can read at a glance: 10 m steps below 100 m, 50 m below 1 km,
     * one decimal below 10 km, whole km above. Imperial uses feet below 0.1 mi.
     */
    fun format(meters: Double, units: DistanceUnits = DistanceUnits.METRIC): FormattedDistance {
        val m = meters.coerceAtLeast(0.0)
        return when (units) {
            DistanceUnits.METRIC -> when {
                m < 100 -> FormattedDistance(((m / 10).roundToInt() * 10).toString(), "m")
                m < 1_000 -> {
                    val r = (m / 50).roundToInt() * 50
                    if (r >= 1_000) FormattedDistance("1.0", "km") else FormattedDistance(r.toString(), "m")
                }
                m < 10_000 -> FormattedDistance(String.format(Locale.ROOT, "%.1f", m / 1_000), "km")
                else -> FormattedDistance((m / 1_000).roundToInt().toString(), "km")
            }
            DistanceUnits.IMPERIAL -> {
                val miles = m / METERS_PER_MILE
                when {
                    miles < 0.1 -> FormattedDistance((((m * FEET_PER_METER) / 50).roundToInt() * 50).toString(), "ft")
                    miles < 10 -> FormattedDistance(String.format(Locale.ROOT, "%.1f", miles), "mi")
                    else -> FormattedDistance(miles.roundToInt().toString(), "mi")
                }
            }
        }
    }
}
