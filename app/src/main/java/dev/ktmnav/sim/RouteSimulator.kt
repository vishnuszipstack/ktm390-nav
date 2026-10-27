package dev.ktmnav.sim

import dev.ktmnav.nav.NavEvent
import dev.ktmnav.nav.NavSource
import dev.ktmnav.source.NavNotificationParser
import dev.ktmnav.source.NavPackages
import dev.ktmnav.source.RawNavNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One step of a scripted route: notification text as a navigation app would post it. */
data class SimStep(val afterMs: Long, val title: String?, val text: String?, val subText: String?, val ended: Boolean = false)

/**
 * Replays notification-shaped text through the real parser and bridge (spec 12.2: "simulator
 * can replay recorded instruction sequences"), so the whole path can be tried without a bike
 * or a live route.
 */
class RouteSimulator(private val scope: CoroutineScope, private val sink: (NavEvent) -> Unit) {

    private var job: Job? = null
    private val _running = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = _running.asStateFlow()

    fun play(name: String, steps: List<SimStep>) {
        stop()
        _running.value = name
        job = scope.launch {
            try {
                for (step in steps) {
                    delay(step.afterMs)
                    val now = System.currentTimeMillis()
                    if (step.ended) {
                        sink(NavEvent.Ended(NavSource.SIMULATOR, now))
                        continue
                    }
                    val event = NavNotificationParser.parse(
                        RawNavNotification(
                            packageName = NavPackages.GOOGLE_MAPS,
                            title = step.title,
                            text = step.text,
                            subText = step.subText,
                            postedAtMs = now,
                        ),
                    )
                    sink(
                        when (event) {
                            is NavEvent.Instruction -> NavEvent.Instruction(event.instruction.copy(source = NavSource.SIMULATOR))
                            is NavEvent.Unparsed -> event.copy(source = NavSource.SIMULATOR)
                            is NavEvent.Ended -> event
                        },
                    )
                }
            } finally {
                _running.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = null
    }

    companion object {
        /** A short city route covering every common manoeuvre, a reroute and arrival. */
        val CITY_LOOP: List<SimStep> = buildList {
            fun s(after: Long, title: String?, text: String?, sub: String?) = add(SimStep(after, title, text, sub))
            s(0, "800 m", "Head north on MG Road", "14 min · 6.2 km · ETA 10:40")
            s(3000, "500 m", "Head north on MG Road", "13 min · 5.9 km · ETA 10:40")
            s(3000, "200 m", "Turn right onto Residency Road", "13 min · 5.6 km · ETA 10:40")
            s(3000, "50 m", "Turn right onto Residency Road", "12 min · 5.4 km · ETA 10:40")
            s(3000, "1.2 km", "At the roundabout, take the 2nd exit onto Richmond Road", "11 min · 5.3 km · ETA 10:41")
            s(3000, "300 m", "At the roundabout, take the 2nd exit onto Richmond Road", "10 min · 4.4 km · ETA 10:41")
            s(3000, "400 m", "Slight left toward Hosur Road", "9 min · 3.9 km · ETA 10:41")
            s(3000, null, "Rerouting…", null)
            s(2000, "250 m", "Make a U-turn", "9 min · 3.8 km · ETA 10:43")
            s(3000, "600 m", "Keep left to stay on Hosur Road", "7 min · 3.0 km · ETA 10:43")
            s(3000, "2.1 km", "Take the exit on the left toward Electronic City", "5 min · 2.3 km · ETA 10:43")
            s(3000, "150 m", "Sharp right onto 2nd Cross", "1 min · 0.2 km · ETA 10:43")
            s(3000, "100 m", "Your destination will be on the left", "1 min · 100 m · ETA 10:43")
            add(SimStep(4000, null, null, null, ended = true))
        }

        /** Stops mid-route without ending: the bike should clear after the stale timeout. */
        val GOES_SILENT: List<SimStep> = listOf(
            SimStep(0, "900 m", "Turn left onto NH 44", "40 min · 32 km · ETA 11:20"),
            SimStep(3000, "700 m", "Turn left onto NH 44", "40 min · 31 km · ETA 11:20"),
        )

        val SCRIPTS: Map<String, List<SimStep>> = linkedMapOf(
            "City loop (all manoeuvres)" to CITY_LOOP,
            "Guidance goes silent (stale test)" to GOES_SILENT,
        )
    }
}
