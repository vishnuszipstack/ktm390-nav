package dev.ktmnav.nav

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Plain-text helpers shared by the notification parsers. English phrasing only for now;
 * anything that doesn't match comes back as null / UNKNOWN rather than a guess.
 */
object TextParsing {

    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_YARD = 0.9144

    // "350 m", "1.2 km", "1,2 km", "0.3 mi", "500 ft", "200 yd", "350m"
    private val DISTANCE = Regex(
        """(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d+)?|\d+(?:[.,]\d+)?)\s*(km|kilometres?|kilometers?|m|metres?|meters?|mi|miles?|ft|feet|yd|yards?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** First distance in [text], converted to metres. */
    fun parseDistanceMeters(text: String?): Double? = findDistances(text).firstOrNull()

    fun findDistances(text: String?): List<Double> {
        if (text.isNullOrBlank()) return emptyList()
        return DISTANCE.findAll(text).mapNotNull { m ->
            val number = parseNumber(m.groupValues[1]) ?: return@mapNotNull null
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val factor = when {
                unit.startsWith("k") -> 1000.0
                unit == "m" || unit.startsWith("met") -> 1.0
                unit.startsWith("mi") -> METERS_PER_MILE
                unit == "ft" || unit == "feet" -> METERS_PER_FOOT
                unit.startsWith("y") -> METERS_PER_YARD
                else -> return@mapNotNull null
            }
            number * factor
        }.toList()
    }

    /** "1.2" / "1,2" (decimal comma) / "1,200" (thousands) → number. */
    internal fun parseNumber(raw: String): Double? {
        val s = raw.trim()
        val normalized = when {
            // 1,200 or 12,500.5 — comma groups of exactly three digits are thousands separators.
            Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""").matches(s) -> s.replace(",", "")
            Regex("""^\d{1,3}(\.\d{3})+(,\d+)?$""").matches(s) && s.count { it == '.' } > 1 ->
                s.replace(".", "").replace(",", ".")
            else -> s.replace(",", ".")
        }
        return normalized.toDoubleOrNull()
    }

    private val HOURS = Regex("""(\d+)\s*(?:h|hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*(?:min|mins|minute|minutes)\b""", RegexOption.IGNORE_CASE)

    /** "1 hr 20 min" → 80, "45 min" → 45, "2 h" → 120. */
    fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val h = HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val m = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (h == null && m == null) return null
        return (h ?: 0) * 60 + (m ?: 0)
    }

    private val CLOCK = Regex("""\b(\d{1,2})[:.](\d{2})\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE)
    private val ETA_HINT = Regex("""\b(eta|arrival|arrive|arriving)\b""", RegexOption.IGNORE_CASE)

    /**
     * An arrival clock time ("ETA 14:35", "2:35 PM ETA", "Arrive 2:35 pm") as epoch millis,
     * resolved to the next occurrence after [nowMs]. Text without an ETA/arrival word is ignored,
     * so a "12:30" in a road name doesn't become an arrival time.
     */
    fun parseEta(text: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (text.isNullOrBlank() || !ETA_HINT.containsMatchIn(text)) return null
        val m = CLOCK.find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val ampm = m.groupValues[3].lowercase(Locale.ROOT).replace(".", "").replace(" ", "")
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var eta = now.with(LocalTime.of(hour, minute))
        if (eta.isBefore(now.minusMinutes(5))) eta = eta.plusDays(1)
        return eta.toInstant().toEpochMilli()
    }

    data class ManeuverMatch(
        val maneuver: Maneuver,
        val confidence: Double,
        val roundaboutExit: Int? = null,
        val rerouting: Boolean = false,
    )

    private val REROUTE = Regex("""\b(rerouting|re-routing|recalculating|finding (a )?(new|better) route)\b""", RegexOption.IGNORE_CASE)
    private val ARRIVE = Regex(
        """\b(you have arrived|you've arrived|arrive at|arriving at|arrived|your destination|destination (is )?(on|will be on) the (left|right)|destination)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ROUNDABOUT = Regex("""\b(roundabout|rotary|traffic circle)\b""", RegexOption.IGNORE_CASE)
    private val ORDINAL_EXIT = Regex(
        """\b(?:take|use)?\s*the\s+(1st|first|2nd|second|3rd|third|4th|fourth|5th|fifth|6th|sixth|7th|seventh|8th|eighth)\s+exit\b""",
        RegexOption.IGNORE_CASE,
    )
    private val UTURN = Regex("""\bu-?\s?turn\b""", RegexOption.IGNORE_CASE)
    private val FERRY = Regex("""\bferry\b""", RegexOption.IGNORE_CASE)
    private val EXIT_OR_RAMP = Regex("""\b(take (the )?exit|exit \d+|take the ramp|ramp|slip road)\b""", RegexOption.IGNORE_CASE)
    private val SHARP = Regex("""\bsharp(?:ly)?\s+(left|right)\b""", RegexOption.IGNORE_CASE)
    private val SLIGHT = Regex("""\b(?:slight(?:ly)?|bear|turn slightly)\s+(left|right)\b""", RegexOption.IGNORE_CASE)
    private val KEEP = Regex("""\b(?:keep|stay|merge)\s+(?:to the\s+)?(left|right)\b""", RegexOption.IGNORE_CASE)
    private val TURN = Regex("""\b(?:turn|go|head)\s+(left|right)\b""", RegexOption.IGNORE_CASE)
    private val BARE_DIRECTION = Regex("""\b(left|right)\b""", RegexOption.IGNORE_CASE)
    private val STRAIGHT = Regex(
        """\b(continue|go straight|straight ahead|keep straight|head (north|south|east|west|northeast|northwest|southeast|southwest)|stay)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Anything after these words is a road or place name and must not be read as a direction. */
    private val ROAD_START = Regex("""\b(onto|on to|toward|towards|into|to stay on|on|for)\b""", RegexOption.IGNORE_CASE)

    /** The part of an instruction that describes the action, before any road name. */
    fun actionPart(text: String): String {
        val m = ROAD_START.find(text) ?: return text
        return text.substring(0, m.range.first)
    }

    private val STRONG_ROAD_START = Regex("""\b(onto|on to|toward|towards|into|to stay on)\b""", RegexOption.IGNORE_CASE)

    /** Text before "onto"/"toward": still describes the action, may say "on the left". */
    private fun beforeRoad(text: String): String {
        val m = STRONG_ROAD_START.find(text) ?: return text
        return text.substring(0, m.range.first)
    }

    /** Road or place name after "onto"/"toward" (or plain "on" when neither is there). */
    fun roadPart(text: String): String? {
        val m = STRONG_ROAD_START.find(text) ?: ROAD_START.find(text) ?: return null
        return text.substring(m.range.last + 1)
            .trim()
            .trimEnd('.', ',', '…')
            .takeIf { it.isNotBlank() }
    }

    fun detectManeuver(raw: String?): ManeuverMatch {
        if (raw.isNullOrBlank()) return ManeuverMatch(Maneuver.UNKNOWN, 0.0)
        val text = raw.replace(' ', ' ')
        if (REROUTE.containsMatchIn(text)) return ManeuverMatch(Maneuver.UNKNOWN, 0.9, rerouting = true)

        val action = actionPart(text)
        fun side(m: MatchResult) = m.groupValues[1].lowercase(Locale.ROOT)

        if (ROUNDABOUT.containsMatchIn(action) || ORDINAL_EXIT.containsMatchIn(action)) {
            val exit = ORDINAL_EXIT.find(action)?.groupValues?.get(1)?.let(::ordinal)
            return ManeuverMatch(Maneuver.ROUNDABOUT, if (exit != null) 0.95 else 0.8, roundaboutExit = exit)
        }
        if (UTURN.containsMatchIn(action)) return ManeuverMatch(Maneuver.U_TURN, 0.95)
        if (FERRY.containsMatchIn(action)) return ManeuverMatch(Maneuver.FERRY, 0.9)
        if (ARRIVE.containsMatchIn(text) && !TURN.containsMatchIn(action)) return ManeuverMatch(Maneuver.ARRIVE, 0.9)

        if (EXIT_OR_RAMP.containsMatchIn(action)) {
            // "Take the exit on the left toward X": the side sits between "on" and "toward".
            val sides = BARE_DIRECTION.findAll(beforeRoad(text)).map(::side).toSet()
            return when (sides) {
                setOf("left") -> ManeuverMatch(Maneuver.EXIT_LEFT, 0.9)
                setOf("right") -> ManeuverMatch(Maneuver.EXIT_RIGHT, 0.9)
                else -> ManeuverMatch(Maneuver.UNKNOWN, 0.4)
            }
        }

        SHARP.find(action)?.let { return ManeuverMatch(if (side(it) == "left") Maneuver.SHARP_LEFT else Maneuver.SHARP_RIGHT, 0.95) }
        SLIGHT.find(action)?.let { return ManeuverMatch(if (side(it) == "left") Maneuver.SLIGHT_LEFT else Maneuver.SLIGHT_RIGHT, 0.95) }
        KEEP.find(action)?.let { return ManeuverMatch(if (side(it) == "left") Maneuver.KEEP_LEFT else Maneuver.KEEP_RIGHT, 0.9) }
        TURN.find(action)?.let { return ManeuverMatch(if (side(it) == "left") Maneuver.LEFT else Maneuver.RIGHT, 0.95) }

        if (STRAIGHT.containsMatchIn(action)) return ManeuverMatch(Maneuver.STRAIGHT, 0.85)

        val sides = BARE_DIRECTION.findAll(action).map(::side).toSet()
        if (sides.size == 1) {
            return ManeuverMatch(if ("left" in sides) Maneuver.LEFT else Maneuver.RIGHT, 0.7)
        }
        if (sides.size > 1) return ManeuverMatch(Maneuver.UNKNOWN, 0.2)
        return ManeuverMatch(Maneuver.UNKNOWN, 0.0)
    }

    private fun ordinal(word: String): Int? = when (word.lowercase(Locale.ROOT)) {
        "1st", "first" -> 1
        "2nd", "second" -> 2
        "3rd", "third" -> 3
        "4th", "fourth" -> 4
        "5th", "fifth" -> 5
        "6th", "sixth" -> 6
        "7th", "seventh" -> 7
        "8th", "eighth" -> 8
        else -> null
    }
}
