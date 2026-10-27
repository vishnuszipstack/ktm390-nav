package dev.ktmnav.source

import dev.ktmnav.nav.NavEvent
import dev.ktmnav.nav.NavSource
import dev.ktmnav.nav.NavigationInstruction
import dev.ktmnav.nav.TextParsing
import java.time.ZoneId

/**
 * The text fields of a navigation app's notification, copied out of the Android
 * Notification so parsing can be unit-tested on the JVM.
 */
data class RawNavNotification(
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    val infoText: String? = null,
    /** Extra lines from inbox-style or custom layouts, in display order. */
    val lines: List<String> = emptyList(),
    val ongoing: Boolean = true,
    val postedAtMs: Long,
)

object NavPackages {
    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val OSMAND = "net.osmand"
    const val OSMAND_PLUS = "net.osmand.plus"
    const val WAZE = "com.waze"

    fun sourceOf(packageName: String): NavSource? = when (packageName) {
        GOOGLE_MAPS -> NavSource.GOOGLE_MAPS
        OSMAND, OSMAND_PLUS -> NavSource.OSMAND
        WAZE -> NavSource.WAZE
        else -> null
    }
}

/**
 * Turns a navigation notification into a [NavEvent].
 *
 * Google Maps' layout is not documented and changes between versions, so this does not
 * depend on which field holds what. It splits every field into segments (on "·", "•", "|",
 * new lines), then:
 * - a segment with a duration, an ETA or "remaining" is the trip summary;
 * - a segment with a manoeuvre phrase is the instruction;
 * - the first distance outside the summary is the distance to the turn.
 * Anything it can't place is left null, never filled with a guess.
 */
object NavNotificationParser {

    private val SPLIT = Regex("""\s*[·•|\n]\s*""")
    private val SUMMARY_HINT = Regex("""\b(min|mins|hr|hrs|hour|hours|eta|arrival|arrive|remaining|left to go)\b""", RegexOption.IGNORE_CASE)
    private val ONLY_DISTANCE = Regex("""^\s*(in\s+)?[\d.,]+\s*(km|m|mi|ft|yd)\s*$""", RegexOption.IGNORE_CASE)

    fun parse(n: RawNavNotification, zone: ZoneId = ZoneId.systemDefault()): NavEvent {
        val source = NavPackages.sourceOf(n.packageName) ?: NavSource.OTHER
        val fields = (listOfNotNull(n.title, n.text, n.bigText, n.subText, n.infoText) + n.lines)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (fields.isEmpty()) return NavEvent.Unparsed(source, "empty notification", n.postedAtMs)

        // A whole field like "13 min · 5.9 km · ETA 10:40" is the trip summary; the others are
        // split into segments to find the instruction and the distance to the turn.
        val summary = fields.filter { SUMMARY_HINT.containsMatchIn(it) && !hasManeuverVerb(it) }
        val rest = (fields - summary.toSet())
            .flatMap { it.split(SPLIT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Instruction: the segment with the most confident manoeuvre.
        val scored = rest.map { it to TextParsing.detectManeuver(it) }
        val best = scored.maxByOrNull { it.second.confidence }
        val instructionText = best?.takeIf { it.second.confidence > 0.0 }?.first
        val match = best?.second ?: TextParsing.ManeuverMatch(dev.ktmnav.nav.Maneuver.UNKNOWN, 0.0)

        // Distance to the turn: prefer a segment that is only a distance ("350 m", "In 1.2 km"),
        // then a distance inside the instruction itself.
        val distance = rest.firstOrNull { ONLY_DISTANCE.matches(it) }?.let(TextParsing::parseDistanceMeters)
            ?: instructionText?.let(TextParsing::parseDistanceMeters)

        val road = instructionText?.let(TextParsing::roadPart)?.let(::cleanRoad)
            // Some layouts put just the road name in the title.
            ?: n.title?.trim()
                ?.takeIf { it in rest && it != instructionText && !ONLY_DISTANCE.matches(it) && TextParsing.detectManeuver(it).confidence == 0.0 }
                ?.let(::cleanRoad)

        val summaryText = summary.joinToString(" · ")
        val remainingMinutes = TextParsing.parseDurationMinutes(summaryText)
        val remainingDistance = summary.asSequence()
            .flatMap { TextParsing.findDistances(it).asSequence() }
            .firstOrNull()
        val eta = TextParsing.parseEta(summaryText, n.postedAtMs, zone)

        if (instructionText == null && distance == null && !match.rerouting) {
            return NavEvent.Unparsed(source, "no manoeuvre or distance found", n.postedAtMs)
        }

        // Missing pieces lower confidence: a turn with no distance is less useful, a
        // distance with no recognisable turn should not be shown as a direction at all.
        var confidence = match.confidence
        if (distance == null && !match.rerouting) confidence *= 0.8

        return NavEvent.Instruction(
            NavigationInstruction(
                source = source,
                maneuver = match.maneuver,
                distanceToManeuverMeters = distance,
                roadName = road,
                remainingDistanceMeters = remainingDistance,
                etaEpochMs = eta,
                remainingMinutes = remainingMinutes,
                roundaboutExit = match.roundaboutExit,
                instructionText = instructionText,
                receivedAtMs = n.postedAtMs,
                confidence = confidence,
                rerouting = match.rerouting,
            ),
        )
    }

    private fun hasManeuverVerb(segment: String): Boolean {
        val m = TextParsing.detectManeuver(segment)
        // "Arrive 14:35" is a summary, not an arrival instruction.
        return m.confidence >= 0.7 && m.maneuver != dev.ktmnav.nav.Maneuver.ARRIVE
    }

    private fun cleanRoad(raw: String): String? = raw
        .replace(Regex("""^(the)\s+""", RegexOption.IGNORE_CASE), "")
        .trim()
        .takeIf { it.isNotBlank() && it.length <= 80 && !SIDE_ONLY.matches(it) }

    private val SIDE_ONLY = Regex("""^(the\s+)?(left|right)$""", RegexOption.IGNORE_CASE)
}
