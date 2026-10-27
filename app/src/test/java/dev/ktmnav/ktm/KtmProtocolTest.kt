package dev.ktmnav.ktm

import dev.ktmnav.nav.DistanceUnits
import dev.ktmnav.nav.Maneuver
import dev.ktmnav.nav.NavSource
import dev.ktmnav.nav.NavigationInstruction
import dev.ktmnav.nav.Units
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtmProtocolTest {

    @Test fun frameHeaderCountsTypeAndPayload() {
        val frame = KtmFrame.encode("{}")
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, '{'.code.toByte(), '}'.code.toByte()), frame)
    }

    @Test fun decoderHandlesSplitAndJoinedFrames() {
        val a = KtmFrame.encode("""{"a":1}""")
        val b = KtmFrame.encode("""{"b":"é"}""")
        val all = a + b
        val d = KtmFrame.Decoder()
        assertTrue(d.feed(all.copyOfRange(0, 3)).isEmpty())
        val first = d.feed(all.copyOfRange(3, a.size + 2))
        assertEquals(listOf(KtmFrame.Frame(1, """{"a":1}""")), first)
        val second = d.feed(all.copyOfRange(a.size + 2, all.size))
        assertEquals("""{"b":"é"}""", second.single().payload)
    }

    @Test fun guidanceJsonHasEveryFieldAndHidesMissingOnes() {
        val json = KtmMessages.toJson(
            DashboardView(UiContext.GUIDANCE, turnIcon = TurnIcon.QUITE_RIGHT, turnDist = "350", turnDistUnit = "m", turnRoad = "B320"),
            7,
        )
        assertTrue(json.startsWith("""{"UiContext":"guidance","UpdateUI":{"""))
        assertTrue(json.contains(""""TurnIcon":{"Image":"QUITE_RIGHT","Visibility":"full"}"""))
        assertTrue(json.contains(""""TurnRoad":{"Text":"B320","Visibility":"full"}"""))
        assertTrue(json.contains(""""ETA":{"Text":"","Visibility":"off"}"""))
        assertTrue(json.contains(""""NotificationIcon":{"Visibility":"off"}"""))
        assertTrue(json.endsWith(""""MsgId":"gon#7"}"""))
        assertTrue(KtmMessages.toJson(DashboardView.RESTORE, 8).contains(""""MsgId":"Restore#8""""))
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", KtmMessages.quote("a\"b\\c\n"))
    }

    @Test fun sequenceIncreases() {
        val a = KtmDisplayAdapter()
        a.encodeRestore()
        a.encodeRestore()
        assertEquals(2, a.sequence)
    }

    @Test fun maneuverIconMapping() {
        assertEquals(TurnIcon.QUITE_LEFT, KtmDisplayAdapter.iconFor(Maneuver.LEFT, null, true))
        assertEquals(TurnIcon.LIGHT_RIGHT, KtmDisplayAdapter.iconFor(Maneuver.SLIGHT_RIGHT, null, true))
        assertEquals(TurnIcon.HEAVY_LEFT, KtmDisplayAdapter.iconFor(Maneuver.SHARP_LEFT, null, true))
        assertEquals(TurnIcon.UTURN_RIGHT, KtmDisplayAdapter.iconFor(Maneuver.U_TURN, null, true))
        assertEquals(TurnIcon.UTURN_LEFT, KtmDisplayAdapter.iconFor(Maneuver.U_TURN, null, false))
        assertEquals(TurnIcon.END, KtmDisplayAdapter.iconFor(Maneuver.ARRIVE, null, true))
        assertEquals(TurnIcon.RAB_SECT_8_LH, KtmDisplayAdapter.iconFor(Maneuver.ROUNDABOUT, 2, true))
        assertEquals(TurnIcon.RAB_SECT_4_RH, KtmDisplayAdapter.iconFor(Maneuver.ROUNDABOUT, 1, false))
        assertEquals(TurnIcon.RAB_SECT_14_LH, KtmDisplayAdapter.iconFor(Maneuver.ROUNDABOUT, 6, true))
        assertNull(KtmDisplayAdapter.iconFor(Maneuver.UNKNOWN, null, true))
        // Every manoeuvre except UNKNOWN has an icon.
        Maneuver.entries.filter { it != Maneuver.UNKNOWN }.forEach {
            assertTrue("$it", KtmDisplayAdapter.iconFor(it, null, true) != null)
        }
    }

    @Test fun guidanceViewFormatsAndRespectsDisabledFields() {
        val zone = ZoneId.of("Asia/Kolkata")
        val eta = ZonedDateTime.of(2026, 10, 27, 14, 35, 0, 0, zone).toInstant().toEpochMilli()
        val i = NavigationInstruction(
            source = NavSource.GOOGLE_MAPS,
            maneuver = Maneuver.RIGHT,
            distanceToManeuverMeters = 1234.0,
            roadName = "Bannerghatta Main Road Extension",
            remainingDistanceMeters = 42_300.0,
            etaEpochMs = eta,
            receivedAtMs = 0,
            confidence = 1.0,
        )
        val adapter = KtmDisplayAdapter(zone = { zone })
        val v = adapter.guidanceView(i)
        assertEquals(TurnIcon.QUITE_RIGHT, v.turnIcon)
        assertEquals("1.2", v.turnDist)
        assertEquals("km", v.turnDistUnit)
        assertEquals("Bannerghatta Main", v.turnRoad)
        assertEquals("42 km", v.dist2Target)
        assertEquals("14:35", v.eta)

        adapter.support = mapOf(DisplayField.ROAD_NAME to FieldSupport.NOT_SHOWN, DisplayField.ETA to FieldSupport.NOT_SHOWN)
        val hidden = adapter.guidanceView(i)
        assertNull(hidden.turnRoad)
        assertNull(hidden.eta)
        assertEquals("1.2", hidden.turnDist)
    }

    @Test fun tftTextIsAsciiAndTruncatedOnWords() {
        assertEquals("Cafe Creme", KtmDisplayAdapter.tftText("Café Crème", 20))
        assertEquals("NH 44", KtmDisplayAdapter.tftText("NH 44", 20))
        assertEquals("Outer Ring", KtmDisplayAdapter.tftText("Outer Ring Road Service", 14))
        assertNull(KtmDisplayAdapter.tftText("ಬೆಂಗಳೂರು", 20))
    }

    @Test fun units() {
        assertEquals("80 m", Units.format(83.0).toString())
        assertEquals("350 m", Units.format(340.0).toString())
        assertEquals("1.0 km", Units.format(990.0).toString())
        assertEquals("4.3 km", Units.format(4321.0).toString())
        assertEquals("12 km", Units.format(12_400.0).toString())
        assertEquals("0.5 mi", Units.format(805.0, DistanceUnits.IMPERIAL).toString())
        assertEquals("300 ft", Units.format(90.0, DistanceUnits.IMPERIAL).toString())
    }
}
