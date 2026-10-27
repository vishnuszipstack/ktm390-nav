package dev.ktmnav.link

/** The bike as Android sees it: an address and the name it was paired under. */
data class BikeDevice(val address: String, val name: String) {
    val isSimulator: Boolean get() = address == SIMULATOR_ADDRESS

    companion object {
        const val SIMULATOR_ADDRESS = "SIMULATOR"
        val SIMULATOR = BikeDevice(SIMULATOR_ADDRESS, "Simulated TFT")
    }
}

/**
 * Connection states from the spec (FR-010). "Connected" means the RFCOMM socket is open;
 * [Connected.lastWriteOkMs] says whether a display message has actually gone out since.
 * Neither proves the TFT drew it: only the rider can confirm that on the Test TFT screen.
 */
sealed interface LinkState {
    val label: String

    data object BluetoothUnavailable : LinkState { override val label = "Bluetooth not available" }
    data object BluetoothOff : LinkState { override val label = "Bluetooth is off" }
    data object NoPermission : LinkState { override val label = "Nearby devices permission needed" }
    data object NotPaired : LinkState { override val label = "Not paired" }
    data class Paired(val device: BikeDevice) : LinkState { override val label = "Paired, not connected" }
    data class Connecting(val device: BikeDevice) : LinkState { override val label = "Connecting" }
    data class Connected(val device: BikeDevice, val sinceMs: Long, val lastWriteOkMs: Long? = null) : LinkState {
        override val label = if (lastWriteOkMs == null) "Connected" else "Connected, display link active"
    }
    data class Reconnecting(val device: BikeDevice, val attempt: Int, val retryInMs: Long) : LinkState {
        override val label = "Reconnecting (attempt $attempt)"
    }
    data class Error(val device: BikeDevice?, val message: String) : LinkState { override val label = "Error: $message" }
}

/** Bounded retry with backoff (FR-013): 1, 2, 4, 8, 15, 30, 30, 30 s, then give up until asked. */
object Backoff {
    private val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000)
    val MAX_ATTEMPTS: Int get() = DELAYS_MS.size

    /** Delay before retry number [attempt] (1-based), or null once retries are exhausted. */
    fun delayFor(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt - 1)
}
