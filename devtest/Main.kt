import app.tvremote.core.*
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

fun waitFile(path: String): String {
    val f = File(path)
    repeat(100) { if (f.exists() && f.length() > 0) return f.readText().trim(); Thread.sleep(100) }
    error("no $path")
}

fun main(args: Array<String>) {
    val log: (String) -> Unit = { println("[client] $it") }
    when (args[0]) {
        "atv" -> {
            val host = args[1]; val port = args[2].toInt()
            val crypto = JdkCrypto()
            val c = CompanionClient(host, port, crypto, log)
            c.startPairing()
            val creds = c.finishPairing(waitFile("/tmp/atv_pin.txt"), "Test Phone")
            c.close()
            println("[client] creds=${creds.serialize()}")
            val parsed = AppleTvCredentials.parse(creds.serialize())
            val r = CompanionClient(host, port, crypto, log)
            r.verify(parsed)
            r.startSession(parsed, "Test Phone")
            for (b in listOf(AtvButton.UP, AtvButton.SELECT, AtvButton.PLAY_PAUSE)) {
                r.press(b, true); r.press(b, false)
            }
            Thread.sleep(1000)
            r.close()
            println("[client] ATV OK")
        }
        "gtv" -> {
            val host = args[1]
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(FileInputStream(args[2]), "pw".toCharArray())
            val alias = ks.aliases().nextElement()
            val id = ClientIdentity(ks.getKey(alias, "pw".toCharArray()) as PrivateKey, ks.getCertificate(alias) as X509Certificate)
            val p = GoogleTvPairing(host, id, log)
            p.start("Test Phone")
            p.finish(waitFile("/tmp/gtv_code.txt"))
            val r = GoogleTvRemote(host, id, log)
            r.connect()
            r.key(GKey.UP); r.key(GKey.CENTER); r.key(GKey.HOME, GKey.START_LONG); r.key(GKey.HOME, GKey.END_LONG)
            Thread.sleep(1500)
            r.close()
            println("[client] GTV OK")
        }
    }
}
