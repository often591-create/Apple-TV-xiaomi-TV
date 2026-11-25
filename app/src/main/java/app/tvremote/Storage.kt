package app.tvremote

import android.content.Context
import app.tvremote.core.ClientIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

enum class TvType { APPLE, GOOGLE }

data class Device(
    val id: String,
    val type: TvType,
    var name: String,
    var host: String,
    var port: Int,
    var credentials: String? = null, // Apple TV pairing keys, or "ok" for Google TV
) {
    val paired get() = credentials != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name)
        .put("host", host).put("port", port).put("credentials", credentials ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Device(
            o.getString("id"), TvType.valueOf(o.getString("type")), o.getString("name"),
            o.getString("host"), o.getInt("port"),
            if (o.isNull("credentials")) null else o.getString("credentials")
        )

        fun new(type: TvType, name: String, host: String, port: Int) =
            Device(UUID.randomUUID().toString(), type, name, host, port)
    }
}

object DeviceStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<Device> {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        return MutableList(arr.length()) { Device.fromJson(arr.getJSONObject(it)) }
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, d: Device) {
        val list = all(ctx).filterNot { it.id == d.id }.toMutableList()
        list.add(d)
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun delete(ctx: Context, id: String) {
        val list = all(ctx).filterNot { it.id == id }
        prefs(ctx).edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}

/** The TLS client certificate this phone uses with Google TV devices (created once). */
object IdentityStore {
    private val pw = "tvremote".toCharArray()
    @Volatile private var cached: ClientIdentity? = null

    @Synchronized
    fun get(ctx: Context): ClientIdentity {
        cached?.let { return it }
        val f = File(ctx.filesDir, "googletv_client.p12")
        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists()) {
            f.inputStream().use { ks.load(it, pw) }
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=atvremote/Android")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 20L * 365 * 86_400_000L), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            ks.load(null, null)
            ks.setKeyEntry("me", kp.private, pw, arrayOf(cert))
            f.outputStream().use { ks.store(it, pw) }
        }
        val id = ClientIdentity(ks.getKey("me", pw) as PrivateKey, ks.getCertificate("me") as X509Certificate)
        cached = id
        return id
    }
}
