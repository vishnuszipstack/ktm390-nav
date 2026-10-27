package dev.ktmnav.nav

import kotlin.math.roundToLong

/**
 * Validation, stale-data filter and duplicate suppression between the parsers and the bike.
 * Pure state machine: the caller supplies the clock, so it is fully unit-testable.
 *
 * Rules (spec FR-020, FR-026, FR-033, 7.1):
 * - Low-confidence or UNKNOWN turns are never sent. The last good turn stays up until it goes stale.
 * - A turn is resent only when something a rider would see changes.
 * - With no fresh update for [staleAfterMs], whatever is shown is cleared.
 * - After a reconnect, only a turn seen in the last [resumeWindowMs] is resent.
 */
class InstructionGate(
    var staleAfterMs: Long = DEFAULT_STALE_MS,
    var resumeWindowMs: Long = DEFAULT_RESUME_MS,
) {
    sealed interface Decision {
        data class Send(val instruction: NavigationInstruction) : Decision
        data class Clear(val reason: String) : Decision
        data class Ignore(val reason: String) : Decision
    }

    /** The instruction currently on the display, or null when nothing is. */
    var current: NavigationInstruction? = null
        private set

    /** When [current] was last confirmed by the source (a duplicate counts). */
    var lastConfirmedMs: Long = 0
        private set

    fun onEvent(event: NavEvent, nowMs: Long): Decision = when (event) {
        is NavEvent.Ended -> clear("navigation ended")
        is NavEvent.Unparsed -> Decision.Ignore("unparsed: ${event.reason}")
        is NavEvent.Instruction -> onInstruction(event.instruction, nowMs)
    }

    private fun onInstruction(i: NavigationInstruction, nowMs: Long): Decision {
        if (nowMs - i.receivedAtMs > staleAfterMs) return Decision.Ignore("arrived stale")
        if (i.rerouting) return clear("rerouting")
        if (i.maneuver == Maneuver.UNKNOWN || i.confidence < MIN_CONFIDENCE) {
            return Decision.Ignore("low confidence (${"%.2f".format(i.confidence)}, ${i.maneuver})")
        }
        if (!isPlausible(i)) return Decision.Ignore("implausible values")

        val shown = current
        lastConfirmedMs = nowMs
        if (shown != null && !meaningfullyDifferent(shown, i)) {
            return Decision.Ignore("duplicate")
        }
        current = i
        return Decision.Send(i)
    }

    /** Called periodically. Clears a turn that the source has stopped confirming. */
    fun tick(nowMs: Long): Decision? {
        if (current == null) return null
        return if (nowMs - lastConfirmedMs > staleAfterMs) clear("stale: no update for ${staleAfterMs / 1000}s") else null
    }

    /** After the link comes back: what to put on the display again, if anything. */
    fun onLinkRestored(nowMs: Long): NavigationInstruction? {
        val shown = current ?: return null
        return if (nowMs - lastConfirmedMs <= resumeWindowMs) shown else {
            current = null
            null
        }
    }

    fun reset() {
        current = null
        lastConfirmedMs = 0
    }

    private fun clear(reason: String): Decision {
        val hadSomething = current != null
        current = null
        return if (hadSomething) Decision.Clear(reason) else Decision.Ignore("$reason (nothing shown)")
    }

    private fun isPlausible(i: NavigationInstruction): Boolean {
        val d = i.distanceToManeuverMeters
        if (d != null && (d < 0 || d > MAX_TURN_DISTANCE_M)) return false
        val r = i.remainingDistanceMeters
        if (r != null && (r < 0 || r > MAX_ROUTE_M)) return false
        return true
    }

    companion object {
        const val DEFAULT_STALE_MS = 30_000L
        const val DEFAULT_RESUME_MS = 10_000L
        const val MIN_CONFIDENCE = 0.6
        private const val MAX_TURN_DISTANCE_M = 2_000_000.0
        private const val MAX_ROUTE_M = 20_000_000.0

        fun meaningfullyDifferent(a: NavigationInstruction, b: NavigationInstruction): Boolean =
            a.maneuver != b.maneuver ||
                a.roundaboutExit != b.roundaboutExit ||
                a.roadName != b.roadName ||
                distanceBucket(a.distanceToManeuverMeters) != distanceBucket(b.distanceToManeuverMeters) ||
                remainingBucket(a.remainingDistanceMeters) != remainingBucket(b.remainingDistanceMeters) ||
                (a.etaEpochMs?.div(60_000)) != (b.etaEpochMs?.div(60_000))

        /** The step a rider can actually see change: 10 m, 50 m, 100 m, then 1 km. */
        fun distanceBucket(m: Double?): Long? = when {
            m == null -> null
            m < 100 -> (m / 10).roundToLong()
            m < 1_000 -> 1_000 + (m / 50).roundToLong()
            m < 10_000 -> 10_000 + (m / 100).roundToLong()
            else -> 100_000 + (m / 1_000).roundToLong()
        }

        private fun remainingBucket(m: Double?): Long? = when {
            m == null -> null
            m < 10_000 -> (m / 100).roundToLong()
            else -> 1_000 + (m / 1_000).roundToLong()
        }
    }
}
