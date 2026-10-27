package dev.ktmnav.nav

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextParsingTest {

    private fun maneuver(text: String) = TextParsing.detectManeuver(text).maneuver

    @Test fun distances() {
        assertEquals(350.0, TextParsing.parseDistanceMeters("350 m")!!, 0.01)
        assertEquals(350.0, TextParsing.parseDistanceMeters("350m")!!, 0.01)
        assertEquals(1200.0, TextParsing.parseDistanceMeters("1.2 km")!!, 0.01)
        assertEquals(1200.0, TextParsing.parseDistanceMeters("1,2 km")!!, 0.01)
        assertEquals(1200.0, TextParsing.parseDistanceMeters("1,200 m")!!, 0.01)
        assertEquals(482.8, TextParsing.parseDistanceMeters("0.3 mi")!!, 0.1)
        assertEquals(152.4, TextParsing.parseDistanceMeters("500 ft")!!, 0.1)
        assertEquals(300.0, TextParsing.parseDistanceMeters("In 300 metres")!!, 0.01)
        assertNull(TextParsing.parseDistanceMeters("Turn left onto MG Road"))
        assertNull(TextParsing.parseDistanceMeters(null))
    }

    @Test fun durations() {
        assertEquals(45, TextParsing.parseDurationMinutes("45 min"))
        assertEquals(80, TextParsing.parseDurationMinutes("1 hr 20 min"))
        assertEquals(120, TextParsing.parseDurationMinutes("2 h"))
        assertNull(TextParsing.parseDurationMinutes("6.2 km"))
    }

    @Test fun etaResolvesToNextOccurrence() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val eta = TextParsing.parseEta("14 min · 6.2 km · ETA 10:40", now, zone)!!
        assertEquals(40L * 60_000, eta - now)
        val pm = TextParsing.parseEta("Arrive 2:35 PM", now, zone)!!
        assertEquals((4L * 60 + 35) * 60_000, pm - now)
        val tomorrow = TextParsing.parseEta("ETA 01:15", now, zone)!!
        assertTrue(tomorrow > now)
        assertNull("no ETA word, no ETA", TextParsing.parseEta("12:30 Road", now, zone))
    }

    @Test fun commonTurns() {
        assertEquals(Maneuver.LEFT, maneuver("Turn left onto MG Road"))
        assertEquals(Maneuver.RIGHT, maneuver("Turn right"))
        assertEquals(Maneuver.SLIGHT_LEFT, maneuver("Slight left toward Hosur Road"))
        assertEquals(Maneuver.SLIGHT_RIGHT, maneuver("Bear right"))
        assertEquals(Maneuver.SHARP_RIGHT, maneuver("Sharp right onto 2nd Cross"))
        assertEquals(Maneuver.KEEP_LEFT, maneuver("Keep left to stay on Hosur Road"))
        assertEquals(Maneuver.KEEP_RIGHT, maneuver("Keep right at the fork"))
        assertEquals(Maneuver.U_TURN, maneuver("Make a U-turn"))
        assertEquals(Maneuver.STRAIGHT, maneuver("Head north on MG Road"))
        assertEquals(Maneuver.STRAIGHT, maneuver("Continue onto NH 44"))
        assertEquals(Maneuver.FERRY, maneuver("Take the ferry"))
    }

    @Test fun roundaboutsAndExits() {
        val r = TextParsing.detectManeuver("At the roundabout, take the 2nd exit onto Richmond Road")
        assertEquals(Maneuver.ROUNDABOUT, r.maneuver)
        assertEquals(2, r.roundaboutExit)
        assertEquals(3, TextParsing.detectManeuver("At the roundabout, take the third exit").roundaboutExit)
        assertEquals(Maneuver.EXIT_LEFT, maneuver("Take the exit on the left toward Electronic City"))
        assertEquals(Maneuver.EXIT_RIGHT, maneuver("Take exit 12 on the right"))
    }

    @Test fun arrival() {
        assertEquals(Maneuver.ARRIVE, maneuver("Your destination will be on the left"))
        assertEquals(Maneuver.ARRIVE, maneuver("You have arrived"))
    }

    @Test fun roadNamesAreNotDirections() {
        // "Right" in the road name must not turn "continue" into a right turn.
        assertEquals(Maneuver.STRAIGHT, maneuver("Continue onto Right Bank Road"))
        assertEquals(Maneuver.LEFT, maneuver("Turn left onto Right Street"))
        assertEquals(Maneuver.STRAIGHT, maneuver("Continue on Left Bank Road"))
    }

    @Test fun unknownNeverBecomesATurn() {
        assertEquals(Maneuver.UNKNOWN, maneuver("Take the exit"))
        assertEquals(Maneuver.UNKNOWN, maneuver("Merge"))
        assertEquals(Maneuver.UNKNOWN, maneuver("Left then right"))
        assertEquals(Maneuver.UNKNOWN, maneuver("Some unrelated text"))
        assertEquals(Maneuver.UNKNOWN, maneuver(""))
    }

    @Test fun rerouting() {
        val r = TextParsing.detectManeuver("Rerouting…")
        assertTrue(r.rerouting)
        assertTrue(TextParsing.detectManeuver("Recalculating route").rerouting)
    }

    @Test fun roadPart() {
        assertEquals("MG Road", TextParsing.roadPart("Turn left onto MG Road"))
        assertEquals("Hosur Road", TextParsing.roadPart("Keep left to stay on Hosur Road"))
        assertEquals("Electronic City", TextParsing.roadPart("Take the exit on the left toward Electronic City"))
        assertNull(TextParsing.roadPart("Turn left"))
    }
}
