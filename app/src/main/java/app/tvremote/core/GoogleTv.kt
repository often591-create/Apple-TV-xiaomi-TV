package app.tvremote.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

// ---------------- Minimal protobuf ----------------

class Pb {
    private val out = ByteArrayOutputStream()
    internal fun varint(v: Long) {
        var x = v
        while (true) {
            if ((x and 0x7FL.inv()) == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }
    fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
    fun bytes(field: Int, b: ByteArray) = apply { varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
    fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, m: Pb) = bytes(field, m.build())
    fun build(): ByteArray = out.toByteArray()

    companion object {
        /** Parses one message into field -> values (Long for varints, ByteArray for length-delimited). */
        fun parse(b: ByteArray): Map<Int, List<Any>> {
            val r = HashMap<Int, MutableList<Any>>()
            var p = 0
            fun rv(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    val c = b[p++].toInt() and 0xFF
                    v = v or ((c and 0x7F).toLong() shl shift)
                    if (c and 0x80 == 0) return v
                    shift += 7
                }
            }
            while (p < b.size) {
                val tag = rv()
                val field = (tag ushr 3).toInt()
                val value: Any = when ((tag and 7).toInt()) {
                    0 -> rv()
                    1 -> { p += 8; continue }
                    2 -> { val n = rv().toInt(); val v = b.copyOfRange(p, p + n); p += n; v }
                    5 -> { p += 4; continue }
                    else -> throw IOException("Bad protobuf wire type")
                }
                r.getOrPut(field) { ArrayList() }.add(value)
            }
            return r
        }

        fun writeDelimited(out: OutputStream, body: ByteArray) {
            val len = Pb().also { it.varint(body.size.toLong()) }.build()
            out.write(len + body)
            out.flush()
        }

        fun readDelimited(input: InputStream): ByteArray {
            var shift = 0; var len = 0L
            while (true) {
                val c = input.read()
                if (c < 0) throw IOException("Connection closed")
                len = len or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) break
                shift += 7
            }
            val buf = ByteArray(len.toInt())
            DataInputStream(input).readFully(buf)
            return buf
        }
    }

}

private fun Map<Int, List<Any>>.msg(f: Int): Map<Int, List<Any>>? = (this[f]?.firstOrNull() as? ByteArray)?.let { Pb.parse(it) }
private fun Map<Int, List<Any>>.long(f: Int): Long? = this[f]?.firstOrNull() as? Long

// ---------------- TLS helpers ----------------

/** Client certificate + key used to identify this phone to the Google TV. */
class ClientIdentity(val key: PrivateKey, val cert: X509Certificate)

object GoogleTvTls {
    fun connect(host: String, port: Int, id: ClientIdentity, timeoutMs: Int = 6000): SSLSocket {
        val km = object : X509KeyManager {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "me"
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("me")
            override fun getCertificateChain(alias: String?) = arrayOf(id.cert)
            override fun getPrivateKey(alias: String?) = id.key
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(km), arrayOf(trustAll), SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.tcpNoDelay = true
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 15000
        s.startHandshake()
        return s
    }
}

// ---------------- Pairing (port 6467) ----------------

class GoogleTvPairing(private val host: String, private val id: ClientIdentity, private val log: (String) -> Unit = {}) : Closeable {
    private var socket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null

    private fun outer(build: (Pb) -> Unit): ByteArray {
        val pb = Pb().int(1, 2).int(2, 200)
        build(pb)
        return pb.build()
    }

    private fun send(b: ByteArray) = Pb.writeDelimited(socket!!.outputStream, b)

    private fun receive(): Map<Int, List<Any>> {
        val m = Pb.parse(Pb.readDelimited(socket!!.inputStream))
        val status = m.long(2) ?: 0
        if (status != 200L) throw AuthException(if (status == 402L) "配对码不正确" else "电视拒绝了配对 (状态 $status)")
        return m
    }

    /** Connects and asks the TV to show a 6-character code. Returns when the code is on screen. */
    fun start(clientName: String) {
        val s = GoogleTvTls.connect(host, 6467, id)
        socket = s
        serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        send(outer { it.msg(10, Pb().str(1, "atvremote").str(2, clientName)) })
        val hex6 = Pb().int(1, 3).int(2, 6)
        while (true) {
            val m = receive()
            when {
                m.containsKey(11) -> send(outer { it.msg(20, Pb().msg(1, hex6).int(3, 1)) })
                m.containsKey(20) -> send(outer { it.msg(30, Pb().msg(1, hex6).int(2, 1)) })
                m.containsKey(31) -> { log("TV is showing the code"); return }
                else -> throw IOException("Unexpected pairing message")
            }
        }
    }

    /** Sends the code shown on the TV. */
    fun finish(code: String) {
        val c = code.trim().uppercase()
        if (c.length != 6 || !c.all { it in "0123456789ABCDEF" }) throw AuthException("配对码应为 6 位（0-9、A-F）")
        val client = id.cert.publicKey as RSAPublicKey
        val server = serverKey!!
        fun b(v: BigInteger) = SrpClient.toBytes(v)
        val hash = Hash.sha256(b(client.modulus), b(client.publicExponent), b(server.modulus), b(server.publicExponent), c.substring(2).unhex())
        if ((hash[0].toInt() and 0xFF) != c.substring(0, 2).toInt(16)) throw AuthException("配对码不正确")
        send(outer { it.msg(40, Pb().bytes(1, hash)) })
        val m = receive()
        if (!m.containsKey(41)) throw IOException("Pairing not acknowledged")
        log("Paired")
        close()
    }

    override fun close() { try { socket?.close() } catch (_: Exception) {} }
}

// ---------------- Remote control (port 6466) ----------------

object GKey {
    const val HOME = 3; const val BACK = 4
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val CENTER = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val POWER = 26
    const val MENU = 82; const val PLAY_PAUSE = 85; const val MUTE = 164; const val SETTINGS = 176
    const val SHORT = 3; const val START_LONG = 1; const val END_LONG = 2
}

class GoogleTvRemote(private val host: String, private val id: ClientIdentity, private val log: (String) -> Unit = {}) : Closeable {
    private var socket: SSLSocket? = null
    private val ready = CountDownLatch(1)
    @Volatile var connected = false
        private set
    @Volatile var onDisconnected: ((Throwable?) -> Unit)? = null
    private val wanted = 1L or 2L or 32L or 64L or 512L // ping, key, power, volume, app link
    private var active = wanted

    @Synchronized
    private fun send(b: ByteArray) = Pb.writeDelimited(socket!!.outputStream, b)

    fun connect() {
        val s = GoogleTvTls.connect(host, 6466, id)
        socket = s
        s.soTimeout = 0
        connected = true
        thread(name = "gtv-reader", isDaemon = true) {
            var err: Throwable? = null
            try {
                while (connected) handle(Pb.parse(Pb.readDelimited(s.inputStream)))
            } catch (e: Throwable) { err = e }
            val was = connected
            close()
            if (was) onDisconnected?.invoke(err)
        }
        if (!ready.await(8, TimeUnit.SECONDS)) {
            close()
            throw IOException("电视没有响应，可能需要重新配对")
        }
    }

    private fun handle(m: Map<Int, List<Any>>) {
        when {
            m.containsKey(1) -> {
                val supported = m.msg(1)?.long(1) ?: wanted
                val info = Pb().str(1, "Phone").str(2, "Android").int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0")
                active = supported and wanted
                send(Pb().msg(1, Pb().int(1, active).msg(2, info)).build())
            }
            m.containsKey(2) -> {
                send(Pb().msg(2, Pb().int(1, active)).build())
                ready.countDown()
            }
            m.containsKey(8) -> {
                val v = m.msg(8)?.long(1) ?: 0
                send(Pb().msg(9, Pb().int(1, v)).build())
            }
            m.containsKey(3) -> log("TV reported an error")
        }
    }

    fun key(code: Int, direction: Int = GKey.SHORT) {
        send(Pb().msg(10, Pb().int(1, code.toLong()).int(2, direction.toLong())).build())
    }

    override fun close() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
    }
}
