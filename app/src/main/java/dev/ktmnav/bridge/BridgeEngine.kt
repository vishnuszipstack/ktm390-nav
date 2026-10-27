package dev.ktmnav.bridge

import dev.ktmnav.diag.DiagCategory
import dev.ktmnav.diag.DiagLog
import dev.ktmnav.ktm.DashboardView
import dev.ktmnav.ktm.KtmDisplayAdapter
import dev.ktmnav.ktm.KtmMessages
import dev.ktmnav.link.BikeLink
import dev.ktmnav.nav.InstructionGate
import dev.ktmnav.nav.NavEvent
import dev.ktmnav.nav.NavSource
import dev.ktmnav.nav.NavigationInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the phone shows about guidance. */
data class BridgeStatus(
    /** Latest instruction parsed from any source, even one not sent to the bike. */
    val latest: NavigationInstruction? = null,
    /** What the TFT should be showing now, or null when it was cleared. */
    val onDisplay: NavigationInstruction? = null,
    val lastNotificationAtMs: Long? = null,
    val lastSource: NavSource? = null,
    val lastDecision: String? = null,
    val lastSentJson: String? = null,
    val lastSentAtMs: Long? = null,
    val navigating: Boolean = false,
)

/**
 * Navigation events in, display frames out:
 *   parser → [InstructionGate] → [KtmDisplayAdapter] → [BikeLink]
 * Everything is synchronous and guarded by one lock, so the gate sees events in order.
 */
class BridgeEngine(
    private val link: BikeLink,
    val adapter: KtmDisplayAdapter,
    val gate: InstructionGate,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    private val lock = Any()

    /** [quiet]: a periodic re-read of a notification still on screen; only log what changes. */
    fun onNavEvent(event: NavEvent, quiet: Boolean = false) = synchronized(lock) {
        val now = clock()
        when (event) {
            is NavEvent.Instruction -> {
                val i = event.instruction
                if (!quiet) log.logSensitive(
                    DiagCategory.PARSER,
                    "${i.source.label}: ${i.maneuver} conf=${"%.2f".format(i.confidence)} dist=${i.distanceToManeuverMeters?.toInt()}",
                    "\"${i.instructionText}\" road=${i.roadName}",
                )
                _status.update { it.copy(latest = i, lastNotificationAtMs = now, lastSource = i.source, navigating = true) }
            }
            is NavEvent.Ended -> {
                log.log(DiagCategory.PARSER, "${event.source.label}: navigation ended")
                _status.update { it.copy(navigating = false, latest = null) }
            }
            is NavEvent.Unparsed -> {
                if (quiet) return@synchronized
                log.log(DiagCategory.PARSER, "${event.source.label}: ${event.reason}")
                _status.update { it.copy(lastNotificationAtMs = now, lastSource = event.source) }
            }
        }
        val decision = gate.onEvent(event, now)
        if (quiet && decision is InstructionGate.Decision.Ignore) return@synchronized
        apply(decision)
    }

    /** Called every couple of seconds by the service. */
    fun tick() = synchronized(lock) {
        gate.tick(clock())?.let(::apply)
    }

    /** The socket just opened. Resend the current turn only if it is still fresh (C2). */
    fun onLinkConnected() = synchronized(lock) {
        val now = clock()
        val fresh = gate.onLinkRestored(now)
        val justSent = status.value.lastSentAtMs?.let { now - it < 1_000 } == true
        if (fresh != null && justSent) {
            log.log(DiagCategory.GATE, "link restored, current turn already sent")
        } else if (fresh != null) {
            send(adapter.guidanceView(fresh), "resume: ${fresh.maneuver}")
        } else {
            log.log(DiagCategory.GATE, "link restored, nothing fresh to resend")
        }
        _status.update { it.copy(onDisplay = fresh) }
    }

    /** Stationary test from the Test TFT screen. Clears the gate so guidance can't mix with it. */
    fun sendTest(view: DashboardView, what: String) = synchronized(lock) {
        gate.reset()
        _status.update { it.copy(onDisplay = null) }
        send(view, "test: $what")
    }

    fun clearDisplay(reason: String) = synchronized(lock) {
        gate.reset()
        _status.update { it.copy(onDisplay = null) }
        send(DashboardView.RESTORE, "clear: $reason")
    }

    private fun apply(decision: InstructionGate.Decision) {
        when (decision) {
            is InstructionGate.Decision.Send -> {
                val i = decision.instruction
                send(adapter.guidanceView(i), "${i.maneuver} ${i.distanceToManeuverMeters?.toInt() ?: "-"} m")
                _status.update { it.copy(onDisplay = i, lastDecision = "sent ${i.maneuver}") }
            }
            is InstructionGate.Decision.Clear -> {
                log.log(DiagCategory.GATE, "clear: ${decision.reason}")
                send(DashboardView.RESTORE, "clear (${decision.reason})")
                _status.update { it.copy(onDisplay = null, lastDecision = "cleared: ${decision.reason}") }
            }
            is InstructionGate.Decision.Ignore -> {
                log.log(DiagCategory.GATE, "ignored: ${decision.reason}")
                _status.update { it.copy(lastDecision = "ignored: ${decision.reason}") }
            }
        }
    }

    private fun send(view: DashboardView, what: String) {
        val frame = adapter.encode(view)
        val json = KtmMessages.toJson(view, adapter.sequence)
        link.send(frame, what)
        _status.update { it.copy(lastSentJson = json, lastSentAtMs = clock()) }
    }
}
