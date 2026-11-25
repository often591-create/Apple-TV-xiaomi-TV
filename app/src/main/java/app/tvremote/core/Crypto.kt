package app.tvremote.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Curve25519 / ChaCha20 primitives. Android uses BouncyCastle; tests can plug in the JDK. */
interface CurveCrypto {
    fun ed25519PublicKey(seed: ByteArray): ByteArray
    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    /** Returns (private, public), both 32 raw bytes. */
    fun x25519KeyPair(): Pair<ByteArray, ByteArray>
    fun x25519(privateKey: ByteArray, peerPublic: ByteArray): ByteArray
    /** Returns ciphertext || 16-byte tag. */
    fun chachaEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plain: ByteArray): ByteArray
    fun chachaDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, sealed: ByteArray): ByteArray
}

object Hash {
    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmac512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        // HMAC with an empty key is defined as a key of zeros.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(64) else key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA512 (RFC 5869). */
    fun hkdf(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac512(salt.toByteArray(), ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac512(prk, t + info.toByteArray() + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    val s = replace(" ", "")
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
