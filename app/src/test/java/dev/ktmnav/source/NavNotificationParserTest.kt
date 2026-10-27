package dev.ktmnav.source

import dev.ktmnav.nav.Maneuver
import dev.ktmnav.nav.NavEvent
import dev.ktmnav.nav.NavSource
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures in the shapes Google Maps has used for its navigation notification. Field
 * placement differs between versions, which is why the parser doesn't rely on it.
 */
class NavNotificationParserTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 10, 27, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun parse(title: String?, text: String?, sub: String? = null, pkg: String = NavPackages.GOOGLE_MAPS) =
        NavNotificationParser.parse(RawNavNotification(pkg, title = title, text = text, subText = sub, postedAtMs = now), zone)

    private fun instruction(e: NavEvent) = (e as NavEvent.Instruction).instruction

    @Test fun distanceInTitleInstructionInText() {
        val i = instruction(parse("350 m", "Turn left onto MG Road", "14 min · 6.2 km · ETA 10:14"))
        assertEquals(NavSource.GOOGLE_MAPS, i.source)
        assertEquals(Maneuver.LEFT, i.maneuver)
        assertEquals(350.0, i.distanceToManeuverMeters!!, 0.01)
        assertEquals("MG Road", i.roadName)
        assertEquals(6200.0, i.remainingDistanceMeters!!, 0.01)
        assertEquals(14, i.remainingMinutes)
        assertEquals(now + 14 * 60_000, i.etaEpochMs)
        assertTrue(i.confidence >= 0.9)
    }

    @Test fun instructionInTitleWithDistance() {
        val i = instruction(parse("In 1.2 km · Slight right toward Hosur Road", "Hosur Road", "1 hr 5 min · 48 km"))
        assertEquals(Maneuver.SLIGHT_RIGHT, i.maneuver)
        assertEquals(1200.0, i.distanceToManeuverMeters!!, 0.01)
        assertEquals("Hosur Road", i.roadName)
        assertEquals(65, i.remainingMinutes)
        assertEquals(48_000.0, i.remainingDistanceMeters!!, 0.01)
    }

    @Test fun roadNameAloneInTitle() {
        val i = instruction(parse("Residency Road", "Turn right · 200 m", null))
        assertEquals(Maneuver.RIGHT, i.maneuver)
        assertEquals(200.0, i.distanceToManeuverMeters!!, 0.01)
        assertEquals("Residency Road", i.roadName)
    }

    @Test fun roundaboutExit() {
        val i = instruction(parse("300 m", "At the roundabout, take the 2nd exit onto Richmond Road"))
        assertEquals(Maneuver.ROUNDABOUT, i.maneuver)
        assertEquals(2, i.roundaboutExit)
        assertEquals("Richmond Road", i.roadName)
    }

    @Test fun arrival() {
        val i = instruction(parse("100 m", "Your destination will be on the left", "1 min · 100 m · ETA 10:01"))
        assertEquals(Maneuver.ARRIVE, i.maneuver)
        assertNull(i.roadName)
    }

    @Test fun rerouting() {
        val i = instruction(parse(null, "Rerouting…"))
        assertTrue(i.rerouting)
    }

    @Test fun distanceWithoutTurnIsUnknownNotAGuess() {
        val i = instruction(parse("500 m", "Kanakapura Road"))
        assertEquals(Maneuver.UNKNOWN, i.maneuver)
        assertEquals(500.0, i.distanceToManeuverMeters!!, 0.01)
    }

    @Test fun summaryOnlyIsUnparsed() {
        assertTrue(parse("Google Maps", "14 min · 6.2 km") is NavEvent.Unparsed)
        assertTrue(parse(null, null) is NavEvent.Unparsed)
    }

    @Test fun otherSources() {
        assertEquals(NavSource.OSMAND, instruction(parse("200 m", "Turn left", pkg = NavPackages.OSMAND)).source)
        assertEquals(NavSource.WAZE, instruction(parse("200 m", "Turn left", pkg = NavPackages.WAZE)).source)
        assertNotNull(NavPackages.sourceOf(NavPackages.GOOGLE_MAPS))
    }
}
