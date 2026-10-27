package dev.ktmnav.nav

import dev.ktmnav.nav.InstructionGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionGateTest {

    private fun instr(
        at: Long,
        m: Maneuver = Maneuver.LEFT,
        dist: Double? = 300.0,
        road: String? = "MG Road",
        conf: Double = 0.95,
        rerouting: Boolean = false,
    ) = NavEvent.Instruction(
        NavigationInstruction(
            source = NavSource.GOOGLE_MAPS,
            maneuver = m,
            distanceToManeuverMeters = dist,
            roadName = road,
            receivedAtMs = at,
            confidence = conf,
            rerouting = rerouting,
        ),
    )

    @Test fun sendsFirstValidInstruction() {
        val g = InstructionGate()
        assertTrue(g.onEvent(instr(0), 0) is Decision.Send)
    }

    @Test fun suppressesDuplicatesAndInvisibleChanges() {
        val g = InstructionGate()
        g.onEvent(instr(0, dist = 300.0), 0)
        assertTrue(g.onEvent(instr(1000, dist = 300.0), 1000) is Decision.Ignore)
        // 310 m and 300 m both read "300 m" on the display.
        assertTrue(g.onEvent(instr(2000, dist = 310.0), 2000) is Decision.Ignore)
        assertTrue(g.onEvent(instr(3000, dist = 250.0), 3000) is Decision.Send)
        assertTrue(g.onEvent(instr(4000, m = Maneuver.RIGHT, dist = 250.0), 4000) is Decision.Send)
    }

    @Test fun lowConfidenceKeepsLastTurnThenItGoesStale() {
        val g = InstructionGate(staleAfterMs = 30_000)
        g.onEvent(instr(0), 0)
        assertTrue(g.onEvent(instr(5_000, m = Maneuver.UNKNOWN, conf = 0.2), 5_000) is Decision.Ignore)
        assertEquals(Maneuver.LEFT, g.current?.maneuver)
        assertNull(g.tick(20_000))
        val d = g.tick(31_000)
        assertTrue(d is Decision.Clear)
        assertNull(g.current)
        assertNull("only clears once", g.tick(40_000))
    }

    @Test fun duplicatesKeepTheTurnAlive() {
        val g = InstructionGate(staleAfterMs = 30_000)
        g.onEvent(instr(0), 0)
        g.onEvent(instr(25_000), 25_000)
        assertNull(g.tick(40_000))
    }

    @Test fun reroutingAndEndClear() {
        val g = InstructionGate()
        g.onEvent(instr(0), 0)
        assertTrue(g.onEvent(instr(1000, m = Maneuver.UNKNOWN, rerouting = true), 1000) is Decision.Clear)
        g.onEvent(instr(2000), 2000)
        assertTrue(g.onEvent(NavEvent.Ended(NavSource.GOOGLE_MAPS, 3000), 3000) is Decision.Clear)
        assertTrue("nothing to clear twice", g.onEvent(NavEvent.Ended(NavSource.GOOGLE_MAPS, 4000), 4000) is Decision.Ignore)
    }

    @Test fun rejectsStaleAndImplausible() {
        val g = InstructionGate(staleAfterMs = 30_000)
        assertTrue(g.onEvent(instr(0), 60_000) is Decision.Ignore)
        assertTrue(g.onEvent(instr(60_000, dist = -5.0), 60_000) is Decision.Ignore)
        assertNull(g.current)
    }

    @Test fun reconnectResendsOnlyFreshTurns() {
        val g = InstructionGate(resumeWindowMs = 10_000)
        g.onEvent(instr(0), 0)
        assertEquals(Maneuver.LEFT, g.onLinkRestored(5_000)?.maneuver)
        assertNull(g.onLinkRestored(15_000))
        assertNull("expired turn is dropped", g.current)
    }

    @Test fun distanceBuckets() {
        assertEquals(InstructionGate.distanceBucket(300.0), InstructionGate.distanceBucket(310.0))
        assertTrue(InstructionGate.distanceBucket(90.0) != InstructionGate.distanceBucket(80.0))
        assertEquals(InstructionGate.distanceBucket(12_300.0), InstructionGate.distanceBucket(12_400.0))
    }
}
