package dev.ktmnav.nav

/** Which app the guidance came from. Each source has its own parser and its own tests. */
enum class NavSource(val label: String) {
    GOOGLE_MAPS("Google Maps"),
    OSMAND("OsmAnd"),
    WAZE("Waze"),
    GPX("GPX"),
    SIMULATOR("Simulator"),
    OTHER("Other"),
}

enum class Maneuver {
    STRAIGHT,
    LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    KEEP_LEFT,
    RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    EXIT_LEFT,
    EXIT_RIGHT,
    FERRY,
    ARRIVE,
    UNKNOWN,
}

/**
 * Provider-independent next-manoeuvre, as described in the spec (6.4).
 * Every distance is in metres; formatting for the TFT or the phone happens later.
 */
data class NavigationInstruction(
    val source: NavSource,
    val maneuver: Maneuver,
    val distanceToManeuverMeters: Double? = null,
    val roadName: String? = null,
    val destinationName: String? = null,
    val remainingDistanceMeters: Double? = null,
    /** Arrival time as epoch millis, when the source states it. */
    val etaEpochMs: Long? = null,
    /** Minutes remaining, when the source states a duration instead of a clock time. */
    val remainingMinutes: Int? = null,
    /** 1-based exit number for roundabouts, when the text names one. */
    val roundaboutExit: Int? = null,
    val instructionText: String? = null,
    val receivedAtMs: Long,
    /** 0..1. Below [InstructionGate.MIN_CONFIDENCE] the instruction is never sent to the bike. */
    val confidence: Double,
    /** The source says it is recalculating; any shown turn is obsolete. */
    val rerouting: Boolean = false,
)

/** What a navigation source can report besides a turn. */
sealed interface NavEvent {
    val atMs: Long

    data class Instruction(val instruction: NavigationInstruction) : NavEvent {
        override val atMs: Long get() = instruction.receivedAtMs
    }

    /** Guidance finished or the navigation notification went away. */
    data class Ended(val source: NavSource, override val atMs: Long) : NavEvent

    /** A notification from a navigation app that we could not read. Logged, never sent to the bike. */
    data class Unparsed(val source: NavSource, val reason: String, override val atMs: Long) : NavEvent
}
