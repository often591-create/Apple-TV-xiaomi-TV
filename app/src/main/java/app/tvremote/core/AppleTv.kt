package app.tvremote.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import kotlin.concurrent.thread

class AuthException(msg: String) : IOException(msg)

/** Long-term pairing keys for one Apple TV. */
data class AppleTvCredentials(
    val ltsk: ByteArray,     // our Ed25519 seed
    val atvLtpk: ByteArray,  // Apple TV Ed25519 public key
    val atvId: ByteArray,
    val clientId: ByteArray,
) {
    fun serialize(): String = listOf(ltsk, atvLtpk, atvId, clientId).joinToString(":") { it.hex() }

    companion object {
        fun parse(s: String): AppleTvCredentials {
            val p = s.split(":").map { it.unhex() }
            return AppleTvCredentials(p[0], p[1], p[2], p[3])
        }
    }
}

/** Buttons of the Siri Remote, as Companion HID command codes. */
enum class AtvButton(val code: Int) {
    UP(1), DOWN(2), LEFT(3), RIGHT(4), MENU(5), SELECT(6), HOME(7),
    VOLUME_UP(8), VOLUME_DOWN(9), SIRI(10), SCREENSAVER(11), SLEEP(12), WAKE(13),
    PLAY_PAUSE(14), CHANNEL_UP(15), CHANNEL_DOWN(16), GUIDE(17), PAGE_UP(18), PAGE_DOWN(19)
}

/**
 * Apple TV "Companion" protocol client (the protocol the iPhone Control Center remote uses).
 * Based on the open-source pyatv implementation.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    private val crypto: CurveCrypto,
    private val log: (String) -> Unit = {},
) : Closeable {
    companion object {
        const val PS_START = 3
        const val PS_NEXT = 4
        const val PV_START = 5
        const val PV_NEXT = 6
        const val E_OPACK = 8
        private val AUTH_FRAMES = setOf(PS_START, PS_NEXT, PV_START, PV_NEXT)

        fun shortNonce(s: String) = ByteArray(4) + s.toByteArray()
    }

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var outKey: ByteArray? = null
    private var inKey: ByteArray? = null
    private var outCounter = 0L
    private var inCounter = 0L
    private var xid = SecureRandom().nextInt(0x10000)
    @Volatile var connected = false
        private set
    @Volatile var onDisconnected: ((Throwable?) -> Unit)? = null

    // pairing state
    private var salt: ByteArray? = null
    private var serverB: ByteArray? = null

    fun connect(timeoutMs: Int = 6000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = 15000
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        output = s.getOutputStream()
        connected = true
    }

    override fun close() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun counterNonce(c: Long) = ByteArray(12) { if (it < 8) ((c ushr (8 * it)) and 0xFF).toByte() else 0 }

    @Synchronized
    private fun sendFrame(type: Int, payload: ByteArray) {
        val key = outKey
        val len = if (key != null && payload.isNotEmpty()) payload.size + 16 else payload.size
        val header = byteArrayOf(type.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        val body = if (key != null && payload.isNotEmpty())
            crypto.chachaEncrypt(key, counterNonce(outCounter++), header, payload) else payload
        output.write(header + body)
        output.flush()
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val header = ByteArray(4)
        input.readFully(header)
        val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val body = ByteArray(len)
        input.readFully(body)
        val type = header[0].toInt() and 0xFF
        val key = inKey
        val payload = if (key != null && len > 0)
            crypto.chachaDecrypt(key, counterNonce(inCounter++), header, body) else body
        return type to payload
    }

    private fun sendOpack(type: Int, data: MutableMap<String, Any?>) {
        if ("_x" !in data) data["_x"] = (xid++).toLong()
        sendFrame(type, Opack.pack(data))
    }

    private fun exchangeAuth(type: Int, data: MutableMap<String, Any?>): Map<Int, ByteArray> {
        sendOpack(type, data)
        val expect = if (type == PS_START || type == PS_NEXT) PS_NEXT else PV_NEXT
        while (true) {
            val (t, payload) = readFrame()
            if (t != expect) continue
            val m = Opack.unpack(payload) as? Map<*, *> ?: throw IOException("Bad auth response")
            val pd = m["_pd"] as? ByteArray ?: throw AuthException("No pairing data")
            val tlv = Tlv8.read(pd)
            tlv[Tlv8.ERROR]?.let {
                val code = it.firstOrNull()?.toInt() ?: 0
                throw AuthException(
                    when (code) {
                        2 -> "PIN 码不正确"
                        3, 5 -> "尝试次数过多，请稍后再试"
                        6 -> "Apple TV 暂时不接受配对"
                        7 -> "Apple TV 正忙，请稍后再试"
                        else -> "配对失败 (错误 $code)"
                    }
                )
            }
            return tlv
        }
    }

    // ---------- Pair setup ----------

    /** Step 1: asks the Apple TV to show a 4-digit PIN on screen. */
    fun startPairing() {
        if (!connected) connect()
        val tlv = exchangeAuth(
            PS_START,
            mutableMapOf(
                "_pd" to Tlv8.write(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1))),
                "_pwTy" to 1L
            )
        )
        salt = tlv[Tlv8.SALT] ?: throw AuthException("No salt")
        serverB = tlv[Tlv8.PUBLIC_KEY] ?: throw AuthException("No public key")
        log("Apple TV is showing a PIN")
    }

    /** Step 2: completes pairing with the PIN shown on the TV. */
    fun finishPairing(pin: String, displayName: String): AppleTvCredentials {
        val srp = SrpClient("Pair-Setup", pin.trim())
        val (pubA, m1) = srp.process(salt!!, serverB!!)
        val r4 = exchangeAuth(
            PS_NEXT,
            mutableMapOf(
                "_pd" to Tlv8.write(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to pubA, Tlv8.PROOF to m1)),
                "_pwTy" to 1L
            )
        )
        val m2 = r4[Tlv8.PROOF] ?: throw AuthException("PIN 码不正确")
        if (!m2.contentEquals(srp.expectedM2())) throw AuthException("PIN 码不正确")

        val k = srp.sessionKey
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ltpk = crypto.ed25519PublicKey(seed)
        val clientId = UUID.randomUUID().toString().uppercase().toByteArray()
        val iosX = Hash.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", k)
        val encKey = Hash.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", k)
        val sig = crypto.ed25519Sign(seed, iosX + clientId + ltpk)
        val inner = Tlv8.write(
            listOf(
                Tlv8.IDENTIFIER to clientId,
                Tlv8.PUBLIC_KEY to ltpk,
                Tlv8.SIGNATURE to sig,
                Tlv8.NAME to Opack.pack(mapOf("name" to displayName)),
            )
        )
        val enc = crypto.chachaEncrypt(encKey, shortNonce("PS-Msg05"), null, inner)
        val r6 = exchangeAuth(
            PS_NEXT,
            mutableMapOf(
                "_pd" to Tlv8.write(listOf(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to enc)),
                "_pwTy" to 1L
            )
        )
        val dec = Tlv8.read(
            crypto.chachaDecrypt(encKey, shortNonce("PS-Msg06"), null, r6[Tlv8.ENCRYPTED_DATA] ?: throw AuthException("No data"))
        )
        val atvId = dec[Tlv8.IDENTIFIER] ?: throw AuthException("No identifier")
        val atvLtpk = dec[Tlv8.PUBLIC_KEY] ?: throw AuthException("No key")
        log("Paired with ${String(atvId)}")
        return AppleTvCredentials(seed, atvLtpk, atvId, clientId)
    }

    // ---------- Pair verify + session ----------

    fun verify(creds: AppleTvCredentials) {
        if (!connected) connect()
        val (priv, pub) = crypto.x25519KeyPair()
        val r2 = exchangeAuth(
            PV_START,
            mutableMapOf(
                "_pd" to Tlv8.write(listOf(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to pub)),
                "_auTy" to 4L
            )
        )
        val serverPub = r2[Tlv8.PUBLIC_KEY] ?: throw AuthException("No public key")
        val shared = crypto.x25519(priv, serverPub)
        val key = Hash.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val inner = Tlv8.read(
            crypto.chachaDecrypt(key, shortNonce("PV-Msg02"), null, r2[Tlv8.ENCRYPTED_DATA] ?: throw AuthException("No data"))
        )
        val atvId = inner[Tlv8.IDENTIFIER]
        val atvSig = inner[Tlv8.SIGNATURE]
        if (atvId == null || atvSig == null || !atvId.contentEquals(creds.atvId) ||
            !crypto.ed25519Verify(creds.atvLtpk, serverPub + atvId + pub, atvSig)
        ) throw AuthException("这台 Apple TV 的配对信息已失效，请重新配对")

        val sig = crypto.ed25519Sign(creds.ltsk, pub + creds.clientId + serverPub)
        val enc = crypto.chachaEncrypt(
            key, shortNonce("PV-Msg03"), null,
            Tlv8.write(listOf(Tlv8.IDENTIFIER to creds.clientId, Tlv8.SIGNATURE to sig))
        )
        exchangeAuth(
            PV_NEXT,
            mutableMapOf("_pd" to Tlv8.write(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to enc)))
        )
        outKey = Hash.hkdf("", "ClientEncrypt-main", shared)
        inKey = Hash.hkdf("", "ServerEncrypt-main", shared)
        outCounter = 0; inCounter = 0
        log("Encrypted session ready")
    }

    private fun request(id: String, content: Map<String, Any?>): Map<*, *> {
        val x = (xid++).toLong()
        sendOpack(E_OPACK, mutableMapOf("_i" to id, "_t" to 2L, "_c" to content, "_x" to x))
        while (true) {
            val (t, payload) = readFrame()
            if (t in AUTH_FRAMES || payload.isEmpty()) continue
            val m = Opack.unpack(payload) as? Map<*, *> ?: continue
            if (m["_t"] == 3L && m["_x"] == x) {
                m["_em"]?.let { throw IOException("$id failed: $it") }
                return m
            }
        }
    }

    /** Opens a remote-control session; call after [verify]. */
    fun startSession(creds: AppleTvCredentials, name: String) {
        val idHex = creds.clientId.copyOf(6).hex()
        request(
            "_systemInfo",
            mapOf(
                "_bf" to 0L, "_cf" to 512L, "_clFl" to 128L,
                "_i" to idHex,
                "_idsID" to creds.clientId,
                "_pubID" to idHex.chunked(2).joinToString(":").uppercase(),
                "_sf" to 256L, "_sv" to "170.18",
                "model" to "iPhone14,3", "name" to name,
            )
        )
        request("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0L, "_width" to 1000.0))
        request(
            "_sessionStart",
            mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to (SecureRandom().nextInt().toLong() and 0xFFFFFFFFL))
        )
        // Optional on newer tvOS; reply (if any) is consumed by the reader thread.
        sendOpack(E_OPACK, mutableMapOf("_i" to "TVRCSessionStart", "_t" to 2L, "_c" to mapOf("ProtocolVersionKey" to "1.2")))

        socket?.soTimeout = 0
        thread(name = "atv-reader", isDaemon = true) {
            var error: Throwable? = null
            try {
                while (connected) readFrame()
            } catch (e: Throwable) { error = e }
            val wasConnected = connected
            close()
            if (wasConnected) onDisconnected?.invoke(error)
        }
    }

    fun press(button: AtvButton, down: Boolean) {
        sendOpack(
            E_OPACK,
            mutableMapOf(
                "_i" to "_hidC", "_t" to 2L,
                "_c" to mapOf("_hBtS" to if (down) 1L else 2L, "_hidC" to button.code.toLong())
            )
        )
    }
}
