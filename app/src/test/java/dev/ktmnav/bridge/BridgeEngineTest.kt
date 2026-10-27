package dev.ktmnav.bridge

import dev.ktmnav.diag.DiagLog
import dev.ktmnav.ktm.KtmDisplayAdapter
import dev.ktmnav.ktm.KtmFrame
import dev.ktmnav.link.BikeDevice
import dev.ktmnav.link.BikeLink
import dev.ktmnav.link.Backoff
import dev.ktmnav.link.LinkState
import dev.ktmnav.link.SimulatedTransport
import dev.ktmnav.nav.InstructionGate
import dev.ktmnav.sim.RouteSimulator
import dev.ktmnav.source.NavNotificationParser
import dev.ktmnav.source.NavPackages
import dev.ktmnav.source.RawNavNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end: notification text → parser → gate → KTM frames → simulated TFT. */
class BridgeEngineTest {

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }

    @Test fun cityLoopReachesTheSimulatedDisplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transports = mutableListOf<SimulatedTransport>()
        val log = DiagLog()
        val link = BikeLink(scope, { SimulatedTransport().also { transports += it } }, log)
        val engine = BridgeEngine(link, KtmDisplayAdapter(), InstructionGate(), log)
        link.onConnected = { engine.onLinkConnected() }

        link.connect(BikeDevice.SIMULATOR)
        waitFor { link.state.value is LinkState.Connected }

        for (step in RouteSimulator.CITY_LOOP.filterNot { it.ended }) {
            engine.onNavEvent(
                NavNotificationParser.parse(
                    RawNavNotification(NavPackages.GOOGLE_MAPS, title = step.title, text = step.text, subText = step.subText, postedAtMs = System.currentTimeMillis()),
                ),
            )
        }
        val t = transports.single()
        waitFor { synchronized(t.received) { t.received.any { it.payload.contains("\"END\"") } } }
        val payloads = synchronized(t.received) { t.received.map(KtmFrame.Frame::payload) }

        assertTrue(payloads.first().contains("GO_STRAIGHT"))
        assertTrue(payloads.any { it.contains("QUITE_RIGHT") && it.contains("Residency Road") })
        assertTrue(payloads.any { it.contains("RAB_SECT_8_LH") })
        assertTrue(payloads.any { it.contains("UTURN_RIGHT") })
        assertTrue(payloads.any { it.contains("LEAVE_HIGHWAY_LEFT_LANE") })
        assertTrue("reroute clears the display", payloads.any { it.contains("\"UiContext\":\"default\"") })
        // Message ids strictly increase.
        val ids = payloads.map { Regex("#(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.distinct(), ids)

        link.disconnect()
        scope.cancel()
    }

    @Test fun reconnectsAfterDropAndDoesNotResendExpiredTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transports = mutableListOf<SimulatedTransport>()
        val log = DiagLog()
        var now = 1_000_000L
        val link = BikeLink(scope, { SimulatedTransport().also { synchronized(transports) { transports += it } } }, log)
        val engine = BridgeEngine(link, KtmDisplayAdapter(), InstructionGate(resumeWindowMs = 10_000), log, clock = { now })
        link.onConnected = { engine.onLinkConnected() }

        link.connect(BikeDevice.SIMULATOR)
        waitFor { link.state.value is LinkState.Connected }
        engine.onNavEvent(
            NavNotificationParser.parse(RawNavNotification(NavPackages.GOOGLE_MAPS, title = "300 m", text = "Turn left", postedAtMs = now)),
        )
        val first = transports.single()
        waitFor { synchronized(first.received) { first.received.isNotEmpty() } }

        // Bike drops out for longer than the resume window.
        now += 60_000
        first.drop()
        waitFor { synchronized(transports) { transports.size } == 2 && link.state.value is LinkState.Connected }
        Thread.sleep(200)
        val second = synchronized(transports) { transports[1] }
        assertTrue("expired turn not resent", synchronized(second.received) { second.received.isEmpty() })
        assertNull(engine.gate.current)

        link.disconnect()
        scope.cancel()
    }

    @Test fun backoffIsBounded() {
        assertEquals(1_000L, Backoff.delayFor(1))
        assertEquals(30_000L, Backoff.delayFor(Backoff.MAX_ATTEMPTS))
        assertNull(Backoff.delayFor(Backoff.MAX_ATTEMPTS + 1))
    }
}
