package app.tvremote.core

import java.math.BigInteger
import java.security.SecureRandom

/** SRP-6a client as used by HomeKit / Apple TV pair-setup (3072-bit group, SHA-512). */
class SrpClient(private val username: String, private val password: String) {
    companion object {
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16
        )
        val g: BigInteger = BigInteger.valueOf(5)
        private val padLen = toBytes(N).size

        /** Minimal big-endian unsigned encoding (at least one byte). */
        fun toBytes(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        fun pad(v: BigInteger): ByteArray {
            val b = toBytes(v)
            return if (b.size >= padLen) b else ByteArray(padLen - b.size) + b
        }

        fun num(b: ByteArray) = BigInteger(1, b)
        private fun h(vararg parts: ByteArray) = Hash.sha512(*parts)
        private fun hn(vararg parts: ByteArray) = num(h(*parts))
    }

    private val a: BigInteger = num(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val publicA: BigInteger = g.modPow(a, N)
    lateinit var sessionKey: ByteArray
        private set
    lateinit var proofM1: ByteArray
        private set

    /** Processes salt and server public key B, returns (A, M1). */
    fun process(salt: ByteArray, serverB: ByteArray): Pair<ByteArray, ByteArray> {
        val B = num(serverB)
        require(B.mod(N) != BigInteger.ZERO) { "Bad server key" }
        val k = hn(toBytes(N), pad(g))
        val u = hn(pad(publicA), pad(B))
        val x = hn(salt, h("$username:$password".toByteArray()))
        val v = g.modPow(x, N)
        val base = B.subtract(k.multiply(v)).mod(N)
        val S = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = h(toBytes(S))
        val hNxorG = hn(toBytes(N)).xor(hn(toBytes(g)))
        proofM1 = h(
            toBytes(hNxorG),
            toBytes(hn(username.toByteArray())),
            salt,
            toBytes(publicA),
            toBytes(B),
            sessionKey
        )
        return toBytes(publicA) to proofM1
    }

    fun expectedM2(): ByteArray = h(toBytes(publicA), proofM1, sessionKey)
}
