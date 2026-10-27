package dev.ktmnav.ktm

import dev.ktmnav.nav.DistanceUnits
import dev.ktmnav.nav.Maneuver
import dev.ktmnav.nav.NavigationInstruction
import dev.ktmnav.nav.Units
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What the rider has confirmed on the Test TFT screen, per display field. */
enum class FieldSupport { UNKNOWN, CONFIRMED, NOT_SHOWN }

enum class DisplayField(val label: String) {
    TURN_ICON("Turn arrow"),
    TURN_DISTANCE("Distance to turn"),
    ROAD_NAME("Road name"),
    DIST_TO_TARGET("Distance to destination"),
    ETA("Arrival time"),
    NOTIFICATION_TEXT("Text line"),
    RESTORE("Return to bike screen"),
}

/**
 * One bike/firmware's link and message format. Everything KTM-specific lives behind
 * this and [KtmDisplayAdapter]; parsers and the UI never see bytes.
 */
data class ProtocolProfile(
    val id: String,
    val name: String,
    val rfcommUuid: UUID,
    val insecureSocket: Boolean,
    /** Longest road name we send; the rest stays on the phone. */
    val maxRoadChars: Int,
    val provenance: String,
    /** Graphics beyond the fixed icon set: nothing documented, so a mini-map is unsupported. */
    val supportsGraphics: Boolean,
) {
    companion object {
        val MY_RIDE_JSON = ProtocolProfile(
            id = "ktm-myride-json-v1",
            name = "KTM MY RIDE (JSON over RFCOMM)",
            rfcommUuid = UUID.fromString("cc4c1fb3-482e-4389-bdeb-57b7aac889ae"),
            insecureSocket = true,
            maxRoadChars = 20,
            provenance = "Community reverse engineering, confirmed on a 790 Adventure. " +
                "Not yet confirmed on a 2022 390 Adventure: use the Test TFT screen while parked.",
            supportsGraphics = false,
        )
    }
}

class KtmDisplayAdapter(
    val profile: ProtocolProfile = ProtocolProfile.MY_RIDE_JSON,
    var support: Map<DisplayField, FieldSupport> = emptyMap(),
    var units: DistanceUnits = DistanceUnits.METRIC,
    /** India drives on the left, so roundabouts run clockwise. */
    var drivesOnLeft: Boolean = true,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Message counter; the dashboard drops updates whose id doesn't increase. */
    var sequence: Long = 0
        private set

    private fun enabled(f: DisplayField) = support[f] != FieldSupport.NOT_SHOWN

    fun guidanceView(i: NavigationInstruction): DashboardView {
        val turn = i.distanceToManeuverMeters?.let { Units.format(it, units) }
        return DashboardView(
            context = UiContext.GUIDANCE,
            turnIcon = iconFor(i.maneuver, i.roundaboutExit, drivesOnLeft).takeIf { enabled(DisplayField.TURN_ICON) },
            turnDist = turn?.value?.takeIf { enabled(DisplayField.TURN_DISTANCE) },
            turnDistUnit = turn?.unit?.takeIf { enabled(DisplayField.TURN_DISTANCE) },
            turnRoad = i.roadName?.let { tftText(it, profile.maxRoadChars) }?.takeIf { enabled(DisplayField.ROAD_NAME) },
            dist2Target = i.remainingDistanceMeters?.let { Units.format(it, units).toString() }
                ?.takeIf { enabled(DisplayField.DIST_TO_TARGET) },
            eta = i.etaEpochMs?.let(::clock)?.takeIf { enabled(DisplayField.ETA) },
        )
    }

    /** A plain text line on the bike's own screen (test messages only). */
    fun textView(message: String) = DashboardView(
        context = UiContext.DEFAULT,
        notificationText = tftText(message, 40),
    )

    fun encode(view: DashboardView): ByteArray {
        sequence += 1
        return KtmFrame.encode(KtmMessages.toJson(view, sequence))
    }

    fun encodeGuidance(i: NavigationInstruction) = encode(guidanceView(i))
    fun encodeRestore() = encode(DashboardView.RESTORE)

    private fun clock(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone()).format(Instant.ofEpochMilli(epochMs))

    companion object {
        /**
         * Normalised manoeuvre → dashboard icon. UNKNOWN never reaches here (the gate drops
         * it), but maps to GO_STRAIGHT-free END-free null-safe default: we refuse to invent a turn.
         */
        fun iconFor(m: Maneuver, roundaboutExit: Int?, drivesOnLeft: Boolean): TurnIcon? = when (m) {
            Maneuver.STRAIGHT -> TurnIcon.GO_STRAIGHT
            Maneuver.LEFT -> TurnIcon.QUITE_LEFT
            Maneuver.SLIGHT_LEFT -> TurnIcon.LIGHT_LEFT
            Maneuver.SHARP_LEFT -> TurnIcon.HEAVY_LEFT
            Maneuver.KEEP_LEFT -> TurnIcon.KEEP_LEFT
            Maneuver.RIGHT -> TurnIcon.QUITE_RIGHT
            Maneuver.SLIGHT_RIGHT -> TurnIcon.LIGHT_RIGHT
            Maneuver.SHARP_RIGHT -> TurnIcon.HEAVY_RIGHT
            Maneuver.KEEP_RIGHT -> TurnIcon.KEEP_RIGHT
            // Left-hand traffic turns round over the right, and vice versa.
            Maneuver.U_TURN -> if (drivesOnLeft) TurnIcon.UTURN_RIGHT else TurnIcon.UTURN_LEFT
            Maneuver.EXIT_LEFT -> TurnIcon.LEAVE_HIGHWAY_LEFT_LANE
            Maneuver.EXIT_RIGHT -> TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE
            Maneuver.FERRY -> TurnIcon.FERRY
            Maneuver.ARRIVE -> TurnIcon.END
            Maneuver.ROUNDABOUT -> roundaboutIcon(roundaboutExit, drivesOnLeft)
            Maneuver.UNKNOWN -> null
        }

        /**
         * The icon set draws the exit as a sector of 16 around the roundabout (8 = straight on).
         * Without the real exit angle we place exit 1/2/3/4+ at 4/8/12/14; the rider can check
         * this on the Test TFT screen. Clockwise roundabouts (left-hand traffic) use the _LH set.
         */
        fun roundaboutIcon(exit: Int?, drivesOnLeft: Boolean): TurnIcon {
            val sector = when (exit) {
                null, 2 -> 8
                1 -> 4
                3 -> 12
                else -> 14
            }
            return TurnIcon.valueOf("RAB_SECT_${sector}_${if (drivesOnLeft) "LH" else "RH"}")
        }

        /** ASCII-only, single line, truncated without a "…" glyph the TFT font may not have. */
        fun tftText(raw: String, maxChars: Int): String? {
            val ascii = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .replace(Regex("""[^\x20-\x7E]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (ascii.isEmpty()) return null
            if (ascii.length <= maxChars) return ascii
            val cut = ascii.substring(0, maxChars)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace >= maxChars / 2) cut.substring(0, lastSpace) else cut).trimEnd()
        }
    }
}
