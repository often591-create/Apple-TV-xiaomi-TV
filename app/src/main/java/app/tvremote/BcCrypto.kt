package app.tvremote

import app.tvremote.core.CurveCrypto
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** BouncyCastle (lightweight API) implementation, works on every Android version. */
object BcCrypto : CurveCrypto {
    override fun ed25519PublicKey(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        s.update(message, 0, message.size)
        return s.verifySignature(signature)
    }

    override fun x25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        return priv.encoded to priv.generatePublicKey().encoded
    }

    override fun x25519(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val a = X25519Agreement()
        a.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(a.agreementSize)
        a.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        return out
    }

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray?, data: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(data.size))
        var n = c.processBytes(data, 0, data.size, out, 0)
        n += c.doFinal(out, n)
        return out.copyOf(n)
    }

    override fun chachaEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plain: ByteArray) = run(true, key, nonce, aad, plain)
    override fun chachaDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray?, sealed: ByteArray) = run(false, key, nonce, aad, sealed)
}
