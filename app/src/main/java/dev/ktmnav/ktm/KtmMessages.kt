package dev.ktmnav.ktm

/** Turn icon names the MY RIDE dashboard draws (community-documented set). */
enum class TurnIcon {
    GO_STRAIGHT,
    LIGHT_LEFT,
    LIGHT_RIGHT,
    QUITE_LEFT,
    QUITE_RIGHT,
    HEAVY_LEFT,
    HEAVY_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    UTURN_LEFT,
    UTURN_RIGHT,
    LEAVE_HIGHWAY_LEFT_LANE,
    LEAVE_HIGHWAY_RIGHT_LANE,
    FERRY,
    END,
    RAB_SECT_4_LH,
    RAB_SECT_4_RH,
    RAB_SECT_6_LH,
    RAB_SECT_6_RH,
    RAB_SECT_8_LH,
    RAB_SECT_8_RH,
    RAB_SECT_10_LH,
    RAB_SECT_10_RH,
    RAB_SECT_12_LH,
    RAB_SECT_12_RH,
    RAB_SECT_14_LH,
    RAB_SECT_14_RH,
    RAB_SECT_16_LH,
    RAB_SECT_16_RH,
}

enum class UiContext(val wire: String, val msgPrefix: String) {
    /** The navigation layout. */
    GUIDANCE("guidance", "gon"),
    /** Hands the screen back to the bike, with an optional notification line. */
    DEFAULT("default", "Restore"),
}

/**
 * Everything the dashboard can draw, as we model it. A null field is sent with
 * `"Visibility": "off"` — the display keeps drawing whatever it last got, so
 * hiding has to be explicit.
 */
data class DashboardView(
    val context: UiContext,
    val turnIcon: TurnIcon? = null,
    val turnDist: String? = null,
    val turnDistUnit: String? = null,
    val turnRoad: String? = null,
    val dist2Target: String? = null,
    val eta: String? = null,
    val turnInfo: String? = null,
    val notificationText: String? = null,
    val gpsIcon: Boolean = true,
) {
    companion object {
        val RESTORE = DashboardView(UiContext.DEFAULT)
    }
}

object KtmMessages {

    /** JSON body for one update. [sequence] must keep increasing or the display ignores it. */
    fun toJson(view: DashboardView, sequence: Long): String {
        val fields = linkedMapOf(
            "TurnRoad" to text(view.turnRoad),
            "TurnDist" to text(view.turnDist),
            "TurnDistUnit" to text(view.turnDistUnit),
            "TurnIcon" to image(view.turnIcon?.name),
            "GpsIcon" to image(if (view.gpsIcon) "GPS" else null),
            "Dist2Target" to text(view.dist2Target),
            "ETA" to text(view.eta),
            "TurnInfo" to text(view.turnInfo),
            "NotificationText" to text(view.notificationText),
            "NotificationIcon" to """{"Visibility":"off"}""",
        )
        val update = fields.entries.joinToString(",") { (k, v) -> "${quote(k)}:$v" }
        return """{"UiContext":${quote(view.context.wire)},"UpdateUI":{$update},"MsgId":${quote("${view.context.msgPrefix}#$sequence")}}"""
    }

    private fun text(value: String?): String =
        """{"Text":${quote(value ?: "")},"Visibility":"${if (value == null) "off" else "full"}"}"""

    private fun image(value: String?): String =
        """{"Image":${quote(value ?: "UNDEFINED")},"Visibility":"${if (value == null) "off" else "full"}"}"""

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
