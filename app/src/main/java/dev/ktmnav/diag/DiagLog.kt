package dev.ktmnav.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagCategory { LINK, PARSER, GATE, TFT, SERVICE, SIM }

data class DiagEntry(val atMs: Long, val category: DiagCategory, val message: String)

/**
 * Local, in-memory diagnostics (FR-015, FR-064). Nothing leaves the phone unless the rider
 * shares a report. Raw notification text is recorded only with [verbose] on.
 */
class DiagLog(private val capacity: Int = 400, private val clock: () -> Long = System::currentTimeMillis) {

    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()

    @Volatile var verbose: Boolean = false

    fun log(category: DiagCategory, message: String) {
        val entry = DiagEntry(clock(), category, message)
        synchronized(this) {
            val list = _entries.value
            _entries.value = if (list.size >= capacity) list.drop(list.size - capacity + 1) + entry else list + entry
        }
    }

    /** Logs [sensitive] only in verbose mode; otherwise only [summary]. */
    fun logSensitive(category: DiagCategory, summary: String, sensitive: String) {
        log(category, if (verbose) "$summary — $sensitive" else summary)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun format(zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)
        return _entries.value.joinToString("\n") {
            "${fmt.format(Instant.ofEpochMilli(it.atMs))} ${it.category.name.padEnd(7)} ${it.message}"
        }
    }

    companion object {
        /** Strips anything that looks like coordinates from a shared report. */
        fun redactCoordinates(text: String): String =
            text.replace(Regex("""-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,}"""), "[coordinates]")
    }
}
