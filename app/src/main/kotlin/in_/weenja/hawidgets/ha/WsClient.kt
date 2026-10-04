package in_.weenja.hawidgets.ha

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal RFC 6455 WebSocket client for Home Assistant's `/api/websocket`: text frames only, client
 * frames masked, fragmented messages joined, pings answered, 64-bit lengths (get_states can be several MB).
 * Blocking; callers run it on Dispatchers.IO. No extensions, no compression, no third-party library.
 */
class WsClient private constructor(private val socket: Socket, private val input: InputStream, private val output: OutputStream) : Closeable {
    private val rnd = SecureRandom()
    @Volatile private var closed = false

    /** Send one text message. */
    @Synchronized
    fun send(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or OP_TEXT)
        val len = payload.size
        when {
            len < 126 -> header.write(0x80 or len)
            len < 65536 -> { header.write(0x80 or 126); header.write(len ushr 8 and 0xff); header.write(len and 0xff) }
            else -> { header.write(0x80 or 127); for (i in 7 downTo 0) header.write(((len.toLong() ushr (8 * i)) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        header.write(mask)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(header.toByteArray()); output.write(payload); output.flush()
    }

    /** Next text message, or null when the server closed the connection. */
    fun receive(): String? {
        val message = ByteArrayOutputStream()
        while (!closed) {
            val b0 = input.read(); if (b0 < 0) return null
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
            if (len > MAX_MESSAGE) throw IOException("WebSocket frame too large: $len")
            val masked = b1 and 0x80 != 0
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val data = ByteArray(len.toInt()).also { readFully(it) }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                OP_TEXT, OP_BINARY, OP_CONT -> {
                    message.write(data)
                    if (message.size() > MAX_MESSAGE) throw IOException("WebSocket message too large")
                    if (fin) return message.toString("UTF-8")
                }
                OP_PING -> sendControl(OP_PONG, data)
                OP_PONG -> {}
                OP_CLOSE -> { closed = true; try { sendControl(OP_CLOSE, ByteArray(0)) } catch (_: IOException) {}; return null }
            }
        }
        return null
    }

    @Synchronized
    private fun sendControl(op: Int, data: ByteArray) {
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or op); frame.write(0x80 or data.size.coerceAtMost(125)); frame.write(mask)
        for (i in 0 until data.size.coerceAtMost(125)) frame.write(data[i].toInt() xor mask[i and 3].toInt())
        output.write(frame.toByteArray()); output.flush()
    }

    private fun readByte(): Int { val b = input.read(); if (b < 0) throw EOFException("WebSocket closed"); return b }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) throw EOFException("WebSocket closed"); off += n }
    }

    override fun close() {
        if (!closed) { closed = true; try { sendControl(OP_CLOSE, byteArrayOf(0x03, 0xe8.toByte())) } catch (_: Exception) {} }
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val OP_CONT = 0x0; private const val OP_TEXT = 0x1; private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8; private const val OP_PING = 0x9; private const val OP_PONG = 0xA
        private const val MAX_MESSAGE = 64L * 1024 * 1024
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Open `<base>/api/websocket` (http -> ws, https -> wss, hostname verified). */
        fun connect(base: String, connectTimeoutMs: Int = 6000, readTimeoutMs: Int = 30000): WsClient {
            val u = URL(base.trimEnd('/'))
            val secure = u.protocol.equals("https", true)
            val port = if (u.port > 0) u.port else if (secure) 443 else 80
            val raw = Socket()
            raw.connect(InetSocketAddress(u.host, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            raw.tcpNoDelay = true
            val sock: Socket = if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, u.host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, ssl.session)) { ssl.close(); throw IOException("Certificate does not match ${u.host}") }
                ssl
            } else raw
            val input = BufferedInputStream(sock.getInputStream(), 65536)
            val output = sock.getOutputStream()
            val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            val path = u.path.trimEnd('/') + "/api/websocket"
            val hostHeader = if (u.port > 0) "${u.host}:${u.port}" else u.host
            val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Homebase\r\n\r\n"
            output.write(req.toByteArray(Charsets.US_ASCII)); output.flush()
            // response head
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read(); if (b < 0) throw EOFException("WebSocket handshake closed")
                head.append(b.toChar()); if (head.length > 16384) throw IOException("WebSocket handshake too long")
            }
            val lines = head.split("\r\n")
            if (!lines.first().contains(" 101")) { sock.close(); throw IOException("WebSocket upgrade refused: ${lines.first()}") }
            val accept = lines.firstOrNull { it.startsWith("sec-websocket-accept:", true) }?.substringAfter(':')?.trim()
            val expect = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()), Base64.NO_WRAP)
            if (accept != expect) { sock.close(); throw IOException("Bad Sec-WebSocket-Accept") }
            return WsClient(sock, input, output)
        }
    }
}
