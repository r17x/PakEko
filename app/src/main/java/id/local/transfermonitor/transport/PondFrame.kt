package id.local.transfermonitor.transport

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object PondFrame {
    const val ENCODING_JSON: Byte = 0x01
    const val MAX_FRAME_SIZE = 16 * 1024 * 1024 // 16 MiB

    fun write(output: OutputStream, payload: ByteArray, encoding: Byte = ENCODING_JSON) {
        val length = 1 + payload.size // encoding byte + payload
        if (length > MAX_FRAME_SIZE) {
            throw PondProtocolException("Frame size $length exceeds max $MAX_FRAME_SIZE")
        }
        val header = ByteBuffer.allocate(4).putInt(length).array()
        output.write(header)
        output.write(encoding.toInt())
        output.write(payload)
        output.flush()
    }

    fun read(input: InputStream): PondRawFrame {
        val header = input.readExactly(4)
            ?: throw PondProtocolException("Connection closed while reading frame header")
        val length = ByteBuffer.wrap(header).int
        if (length <= 0 || length > MAX_FRAME_SIZE) {
            throw PondProtocolException("Invalid frame length: $length")
        }
        val encoding = input.read()
        if (encoding == -1) {
            throw PondProtocolException("Connection closed while reading encoding byte")
        }
        val payload = input.readExactly(length - 1)
            ?: throw PondProtocolException("Connection closed while reading payload")
        return PondRawFrame(
            encoding = encoding.toByte(),
            payload = payload,
        )
    }

    private fun InputStream.readExactly(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val bytesRead = read(buf, offset, n - offset)
            if (bytesRead == -1) return if (offset == 0) null else throw PondProtocolException("Unexpected end of stream")
            offset += bytesRead
        }
        return buf
    }
}

data class PondRawFrame(
    val encoding: Byte,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PondRawFrame) return false
        return encoding == other.encoding && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * encoding.hashCode() + payload.contentHashCode()
}

class PondProtocolException(message: String) : Exception(message)
