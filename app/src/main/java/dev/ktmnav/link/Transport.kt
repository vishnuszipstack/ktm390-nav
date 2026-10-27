package dev.ktmnav.link

import dev.ktmnav.ktm.KtmFrame
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A byte pipe to the bike. Blocking calls; [BikeLink] runs them on the IO dispatcher. */
interface Transport {
    fun open()
    fun write(bytes: ByteArray)
    /** Blocks until bytes arrive; returns -1 at end of stream. */
    fun read(buffer: ByteArray): Int
    fun close()
}

/**
 * Stand-in for the TFT: decodes every frame it receives and remembers the last one, so the
 * simulator and the unit tests exercise exactly the bytes a bike would get.
 */
class SimulatedTransport(
    private val onFrame: (KtmFrame.Frame) -> Unit = {},
) : Transport {
    private val decoder = KtmFrame.Decoder()
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var open = false
    @Volatile var dropNextWrite = false

    val received = mutableListOf<KtmFrame.Frame>()

    override fun open() {
        open = true
    }

    override fun write(bytes: ByteArray) {
        if (!open) throw IOException("simulated link closed")
        if (dropNextWrite) {
            dropNextWrite = false
            open = false
            throw IOException("simulated link dropped")
        }
        val frames = decoder.feed(bytes)
        synchronized(received) { received += frames }
        frames.forEach(onFrame)
    }

    override fun read(buffer: ByteArray): Int {
        while (open) {
            val chunk = incoming.poll(250, TimeUnit.MILLISECONDS) ?: continue
            val n = minOf(chunk.size, buffer.size)
            chunk.copyInto(buffer, 0, 0, n)
            return n
        }
        throw IOException("simulated link closed")
    }

    /** Simulate the bike going out of range. */
    fun drop() {
        open = false
    }

    override fun close() {
        open = false
    }
}
