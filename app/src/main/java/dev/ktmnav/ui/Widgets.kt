package dev.ktmnav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ktmnav.nav.Maneuver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

enum class Check { OK, WARN, FAIL, UNKNOWN }

/** A status line with an icon and words, never colour alone (7.5). */
@Composable
fun CheckRow(state: Check, title: String, detail: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val (icon, tint) = when (state) {
            Check.OK -> Icons.Filled.CheckCircle to NavColors.Ok
            Check.WARN -> Icons.Filled.Warning to NavColors.Warning
            Check.FAIL -> Icons.Filled.Error to NavColors.Danger
            Check.UNKNOWN -> Icons.Filled.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = state.name, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * Phone-side turn arrow. Drawn, not an image, so it stays sharp at any size; it mirrors
 * the manoeuvre, not the TFT's own icon artwork.
 */
@Composable
fun TurnArrow(maneuver: Maneuver?, roundaboutExit: Int?, size: Dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val c = Offset(w / 2, w / 2)

        fun arrowTo(angleDeg: Float) {
            // Stem from the bottom, bend at the centre, head at angle (0 = straight up, + = right).
            val stem = Path().apply {
                moveTo(c.x, w * 0.92f)
                lineTo(c.x, c.y)
            }
            drawPath(stem, color, style = stroke)
            rotate(angleDeg, c) {
                val head = Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x, w * 0.14f)
                    moveTo(c.x - w * 0.16f, w * 0.30f)
                    lineTo(c.x, w * 0.12f)
                    lineTo(c.x + w * 0.16f, w * 0.30f)
                }
                drawPath(head, color, style = stroke)
            }
        }

        when (maneuver) {
            Maneuver.STRAIGHT -> arrowTo(0f)
            Maneuver.LEFT -> arrowTo(-90f)
            Maneuver.RIGHT -> arrowTo(90f)
            Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT, Maneuver.EXIT_LEFT -> arrowTo(-40f)
            Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.EXIT_RIGHT -> arrowTo(40f)
            Maneuver.SHARP_LEFT -> arrowTo(-140f)
            Maneuver.SHARP_RIGHT -> arrowTo(140f)
            Maneuver.U_TURN -> {
                val p = Path().apply {
                    moveTo(w * 0.62f, w * 0.92f)
                    lineTo(w * 0.62f, w * 0.40f)
                    cubicTo(w * 0.62f, w * 0.12f, w * 0.30f, w * 0.12f, w * 0.30f, w * 0.40f)
                    lineTo(w * 0.30f, w * 0.70f)
                    moveTo(w * 0.16f, w * 0.56f)
                    lineTo(w * 0.30f, w * 0.72f)
                    lineTo(w * 0.44f, w * 0.56f)
                }
                drawPath(p, color, style = stroke)
            }
            Maneuver.ROUNDABOUT -> {
                drawCircle(color, radius = w * 0.18f, center = c, style = stroke)
                val exitAngle = when (roundaboutExit) { 1 -> 90f; 3 -> -90f; null, 2 -> 0f; else -> -135f }
                rotate(exitAngle, c) {
                    val p = Path().apply {
                        moveTo(c.x, c.y - w * 0.18f)
                        lineTo(c.x, w * 0.10f)
                        moveTo(c.x - w * 0.13f, w * 0.24f)
                        lineTo(c.x, w * 0.09f)
                        lineTo(c.x + w * 0.13f, w * 0.24f)
                    }
                    drawPath(p, color, style = stroke)
                }
                drawLine(color, Offset(c.x, w * 0.94f), Offset(c.x, c.y + w * 0.18f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            Maneuver.ARRIVE -> {
                drawCircle(color, radius = w * 0.26f, center = Offset(c.x, w * 0.40f), style = stroke)
                drawCircle(color, radius = w * 0.08f, center = Offset(c.x, w * 0.40f))
                drawLine(color, Offset(c.x, w * 0.66f), Offset(c.x, w * 0.92f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            Maneuver.FERRY -> {
                val p = Path().apply {
                    moveTo(w * 0.14f, w * 0.58f)
                    lineTo(w * 0.86f, w * 0.58f)
                    lineTo(w * 0.72f, w * 0.80f)
                    lineTo(w * 0.28f, w * 0.80f)
                    close()
                    moveTo(w * 0.36f, w * 0.58f)
                    lineTo(w * 0.36f, w * 0.34f)
                    lineTo(w * 0.64f, w * 0.34f)
                    lineTo(w * 0.64f, w * 0.58f)
                }
                drawPath(p, color, style = stroke)
            }
            Maneuver.UNKNOWN, null -> {
                drawCircle(color.copy(alpha = 0.35f), radius = w * 0.34f, center = c, style = stroke)
            }
        }
    }
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatClock(epochMs: Long): String = clockFmt.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
fun formatTime(epochMs: Long): String = timeFmt.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

fun ago(epochMs: Long?, nowMs: Long): String {
    if (epochMs == null) return "never"
    val s = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}

fun maneuverLabel(m: Maneuver, exit: Int?): String = when (m) {
    Maneuver.STRAIGHT -> "Continue straight"
    Maneuver.LEFT -> "Turn left"
    Maneuver.SLIGHT_LEFT -> "Slight left"
    Maneuver.SHARP_LEFT -> "Sharp left"
    Maneuver.KEEP_LEFT -> "Keep left"
    Maneuver.RIGHT -> "Turn right"
    Maneuver.SLIGHT_RIGHT -> "Slight right"
    Maneuver.SHARP_RIGHT -> "Sharp right"
    Maneuver.KEEP_RIGHT -> "Keep right"
    Maneuver.U_TURN -> "U-turn"
    Maneuver.ROUNDABOUT -> if (exit != null) "Roundabout, exit $exit" else "Roundabout"
    Maneuver.EXIT_LEFT -> "Exit left"
    Maneuver.EXIT_RIGHT -> "Exit right"
    Maneuver.FERRY -> "Ferry"
    Maneuver.ARRIVE -> "Arrive"
    Maneuver.UNKNOWN -> "Direction unclear"
}
