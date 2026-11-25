package app.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

data class Found(val type: TvType, val name: String, val host: String, val port: Int)

/** Finds Apple TVs (_companion-link) and Google TVs (_androidtvremote2) on the Wi-Fi network. */
class Discovery(ctx: Context, private val onFound: (Found) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private val queue = ArrayDeque<Pair<NsdServiceInfo, TvType>>()
    private var resolving = false

    fun start() {
        lock = wifi.createMulticastLock("tvremote").apply { setReferenceCounted(false); acquire() }
        listen("_companion-link._tcp", TvType.APPLE)
        listen("_androidtvremote2._tcp", TvType.GOOGLE)
    }

    fun stop() {
        listeners.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listeners.clear()
        lock?.release()
        main.removeCallbacksAndMessages(null)
    }

    private fun listen(service: String, type: TvType) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue.addLast(info to type); next() }
            }
        }
        listeners.add(l)
        try { nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    // Older Android versions only allow one resolve at a time.
    private fun next() {
        if (resolving) return
        val (info, type) = queue.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; next() }
            }

            override fun onServiceResolved(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val addr = i.host
                val model = i.attributes["rpMd"]?.let { String(it) } ?: ""
                // _companion-link is also used by iPhones, Macs and HomePods: keep Apple TVs only.
                val keep = type == TvType.GOOGLE || model.isEmpty() || model.startsWith("AppleTV")
                if (addr != null && keep) {
                    val f = Found(type, i.serviceName, addr.hostAddress ?: "", i.port)
                    main.post { onFound(f) }
                }
                main.post { resolving = false; next() }
            }
        })
    }
}
