import app.tvremote.core.CurveCrypto
import java.math.BigInteger
import java.security.*
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JDK-only implementation used to test the protocol code on a desktop JVM. */
class JdkCrypto : CurveCrypto {
    private class FixedRandom(val b: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) { System.arraycopy(b, 0, bytes, 0, bytes.size) }
    }

    private fun edPair(seed: ByteArray): KeyPair {
        val g = KeyPairGenerator.getInstance("Ed25519")
        g.initialize(NamedParameterSpec.ED25519, FixedRandom(seed))
        return g.generateKeyPair()
    }

    override fun ed25519PublicKey(seed: ByteArray): ByteArray {
        val p = (edPair(seed).public as EdECPublicKey).point
        val y = p.y.toByteArray().reversedArray().copyOf(32)
        if (p.isXOdd) y[31] = (y[31].toInt() or 0x80).toByte()
        return y
    }

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(edPair(seed).private)
        s.update(message)
        return s.sign()
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val b = publicKey.copyOf()
        val xOdd = (b[31].toInt() and 0x80) != 0
        b[31] = (b[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, b.reversedArray())
        val key = KeyFactory.getInstance("Ed25519").generatePublic(EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, y)))
        val s = Signature.getInstance("Ed25519")
        s.initVerify(key)
        s.update(message)
        return s.verify(signature)
    }

    override fun x25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val priv = (kp.private as XECPrivateKey).scalar.get()
        val u = (kp.public as XECPublicKey).u
        return priv to u.toByteArray().reversedArray().copyOf(32)
    }

    override fun x25519(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        val priv = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey))
        val pub = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, peerPublic.reversedArray())))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    private fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    override fun chachaEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plain: ByteArray) =
        chacha(Cipher.ENCRYPT_MODE, key, nonce, aad, plain)

    override fun chachaDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, sealed: ByteArray) =
        chacha(Cipher.DECRYPT_MODE, key, nonce, aad, sealed)
}
