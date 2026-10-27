package dev.ktmnav.link

import dev.ktmnav.diag.DiagCategory
import dev.ktmnav.diag.DiagLog
import dev.ktmnav.ktm.KtmFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the one connection to the bike (FR-012): never two sockets, every write goes
 * through one queue, and a dropped link is retried with [Backoff] (FR-011, FR-013).
 *
 * [onConnected] runs each time the socket opens, so the bridge can decide what (if
 * anything) is still fresh enough to put back on the display.
 */
class BikeLink(
    private val scope: CoroutineScope,
    private val transportFor: (BikeDevice) -> Transport,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.NotPaired)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    var onConnected: (suspend () -> Unit)? = null

    /** Only the newest few frames matter; a backlog of old turns is worse than none. */
    private val outbox = Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lifecycle = Mutex()
    private var job: Job? = null
    private var device: BikeDevice? = null
    private var retryNow = CompletableDeferred<Unit>()

    /** The open transport, so cancelling can close it and unblock a pending read. */
    @Volatile private var current: Transport? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** Show a state that comes from outside the link (Bluetooth off, no permission, paired). */
    fun reportIdle(state: LinkState) {
        if (!isRunning) _state.value = state
    }

    suspend fun connect(target: BikeDevice) = lifecycle.withLock {
        if (job?.isActive == true && device == target) {
            // Already on it: a manual "Connect" skips the current backoff wait instead.
            retryNow.complete(Unit)
            return@withLock
        }
        stopJob()
        device = target
        job = scope.launch { run(target) }
    }

    suspend fun disconnect() = lifecycle.withLock {
        stopJob()
        job = null
        drainOutbox()
        _state.value = device?.let { LinkState.Paired(it) } ?: LinkState.NotPaired
    }

    /** Queue a frame. Dropped (and logged) if there is no open link: never sent later out of context. */
    fun send(frame: ByteArray, what: String) {
        if (_state.value !is LinkState.Connected) {
            log.log(DiagCategory.TFT, "not sent (no link): $what")
            return
        }
        outbox.trySend(frame)
        log.log(DiagCategory.TFT, "queued: $what")
    }

    private suspend fun stopJob() {
        current?.let { t -> withContext(io) { runCatching { t.close() } } }
        job?.cancelAndJoin()
    }

    private fun drainOutbox() {
        while (outbox.tryReceive().isSuccess) Unit
    }

    private suspend fun run(target: BikeDevice) {
        var attempt = 0
        while (true) {
            _state.value = if (attempt == 0) LinkState.Connecting(target) else _state.value
            val transport = transportFor(target)
            current = transport
            try {
                withContext(io) { transport.open() }
                attempt = 0
                drainOutbox()
                _state.value = LinkState.Connected(target, clock())
                log.log(DiagCategory.LINK, "connected to ${target.name}")
                session(transport)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + io) { transport.close() }
                throw e
            } catch (e: Exception) {
                log.log(DiagCategory.LINK, "link ${if (attempt == 0) "lost" else "failed"}: ${e.message ?: e.javaClass.simpleName}")
            }
            withContext(io) { runCatching { transport.close() } }
            current = null

            attempt += 1
            val wait = Backoff.delayFor(attempt)
            if (wait == null) {
                _state.value = LinkState.Error(target, "bike not reachable after ${Backoff.MAX_ATTEMPTS} tries")
                log.log(DiagCategory.LINK, "giving up until reconnect is requested or the bike reappears")
                awaitRetry(Long.MAX_VALUE)
                attempt = 0
                continue
            }
            _state.value = LinkState.Reconnecting(target, attempt, wait)
            awaitRetry(wait)
        }
    }

    private suspend fun awaitRetry(ms: Long) {
        retryNow = CompletableDeferred()
        val signal = retryNow
        if (ms == Long.MAX_VALUE) signal.await() else withTimeoutOrNull(ms) { signal.await() }
    }

    /** Runs until the socket fails: one reader, one writer. */
    private suspend fun session(transport: Transport): Nothing = coroutineScope {
        onConnected?.invoke()
        // A blocking read only returns when the socket closes, so any failure closes it first.
        fun failed(e: Throwable): Nothing {
            runCatching { transport.close() }
            throw e
        }
        val reader = launch(io) {
            try {
                val decoder = KtmFrame.Decoder()
                val buf = ByteArray(4096)
                while (isActive) {
                    val n = transport.read(buf)
                    if (n < 0) throw java.io.IOException("bike closed the link")
                    runCatching { decoder.feed(buf, n) }
                        .onSuccess { frames -> frames.forEach { log.log(DiagCategory.LINK, "bike sent type ${it.type}, ${it.payload.length} chars") } }
                        .onFailure { log.log(DiagCategory.LINK, "unreadable bytes from bike ($n bytes)") }
                }
            } catch (e: Throwable) {
                failed(e)
            }
        }
        val writer = launch(io) {
            try {
                for (frame in outbox) {
                    transport.write(frame)
                    val s = _state.value
                    if (s is LinkState.Connected) _state.value = s.copy(lastWriteOkMs = clock())
                }
            } catch (e: Throwable) {
                failed(e)
            }
        }
        // Either side failing ends the session; the other is cancelled with it.
        try {
            select {
                reader.onJoin { }
                writer.onJoin { }
            }
        } finally {
            reader.cancel()
            writer.cancel()
        }
        throw java.io.IOException("link closed")
    }

    /** Called when Android reports the bike reconnected at the ACL level. */
    fun bikeAppeared() {
        if (isRunning) retryNow.complete(Unit)
    }
}
