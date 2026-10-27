package dev.ktmnav.ktm

import java.io.ByteArrayOutputStream

/**
 * Framing on the KTM MY RIDE serial (RFCOMM) link, as described by community
 * reverse engineering (not a KTM specification):
 *
 *   | length: uint32 big-endian | type: 1 byte | payload |
 *
 * `length` counts the type byte plus the payload. Type 0x01 carries UTF-8 JSON.
 */
object KtmFrame {
    const val TYPE_JSON: Int = 0x01
    const val HEADER_BYTES = 5
    const val MAX_FRAME_BYTES = 64 * 1024

    fun encode(payload: String, type: Int = TYPE_JSON): ByteArray {
        val body = payload.toByteArray(Charsets.UTF_8)
        val length = body.size + 1
        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = type.toByte()
        body.copyInto(out, HEADER_BYTES)
        return out
    }

    data class Frame(val type: Int, val payload: String)

    /**
     * Stream decoder for anything the bike sends back. Feed it bytes as they arrive;
     * it returns whole frames and keeps the remainder.
     */
    class Decoder {
        private val buffer = ByteArrayOutputStream()

        fun feed(bytes: ByteArray, count: Int = bytes.size): List<Frame> {
            buffer.write(bytes, 0, count)
            val data = buffer.toByteArray()
            val frames = mutableListOf<Frame>()
            var offset = 0
            while (data.size - offset >= HEADER_BYTES) {
                val length = ((data[offset].toInt() and 0xff) shl 24) or
                    ((data[offset + 1].toInt() and 0xff) shl 16) or
                    ((data[offset + 2].toInt() and 0xff) shl 8) or
                    (data[offset + 3].toInt() and 0xff)
                if (length < 1 || length > MAX_FRAME_BYTES) {
                    // Out of sync or not a framed stream: drop what we have rather than misread it.
                    buffer.reset()
                    throw IllegalStateException("Invalid KTM frame length $length")
                }
                val total = 4 + length
                if (data.size - offset < total) break
                val type = data[offset + 4].toInt() and 0xff
                val payload = String(data, offset + HEADER_BYTES, length - 1, Charsets.UTF_8)
                frames += Frame(type, payload)
                offset += total
            }
            buffer.reset()
            if (offset < data.size) buffer.write(data, offset, data.size - offset)
            return frames
        }
    }
}
