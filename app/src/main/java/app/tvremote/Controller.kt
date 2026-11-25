package app.tvremote

import android.content.Context
import app.tvremote.core.AppleTvCredentials
import app.tvremote.core.AtvButton
import app.tvremote.core.CompanionClient
import app.tvremote.core.GKey
import app.tvremote.core.GoogleTvRemote
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class Btn { POWER, HOME, BACK, UP, DOWN, LEFT, RIGHT, OK, PLAY_PAUSE, VOL_UP, VOL_DOWN, MUTE, MENU }

/** Common remote interface for both TV types. All network work runs on one background thread. */
abstract class Controller {
    protected val exec = Executors.newSingleThreadScheduledExecutor()
    var onStatus: ((String) -> Unit)? = null

    abstract val supported: Set<Btn>
    protected abstract fun open()
    protected abstract fun isOpen(): Boolean
    protected abstract fun shutdown()

    fun connect() = exec.execute { ensure() }

    protected fun ensure(): Boolean {
        if (isOpen()) return true
        onStatus?.invoke("连接中…")
        return try {
            open()
            onStatus?.invoke("已连接")
            true
        } catch (e: Exception) {
            shutdown()
            onStatus?.invoke("连接失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Runs [action], reconnecting once if the connection has dropped. */
    protected fun send(action: () -> Unit) = exec.execute {
        if (!ensure()) return@execute
        try {
            action()
        } catch (e: Exception) {
            shutdown()
            if (ensure()) try { action() } catch (_: Exception) { shutdown() }
        }
    }

    abstract fun down(b: Btn)
    abstract fun up(b: Btn)

    fun close() {
        exec.execute { shutdown() }
        exec.shutdown()
    }

    companion object {
        fun create(ctx: Context, d: Device): Controller = when (d.type) {
            TvType.APPLE -> AppleController(d)
            TvType.GOOGLE -> GoogleController(ctx.applicationContext, d)
        }
    }
}

class AppleController(private val d: Device) : Controller() {
    private var client: CompanionClient? = null
    private var asleep = false
    override val supported = Btn.values().toSet() - Btn.MUTE - Btn.MENU

    override fun open() {
        val creds = AppleTvCredentials.parse(d.credentials!!)
        val c = CompanionClient(d.host, d.port, BcCrypto)
        client = c
        c.verify(creds)
        c.startSession(creds, "Android 遥控器")
        c.onDisconnected = { onStatus?.invoke("连接已断开，按任意键重连") }
    }

    override fun isOpen() = client?.connected == true
    override fun shutdown() { client?.close(); client = null }

    private fun map(b: Btn): AtvButton? = when (b) {
        Btn.UP -> AtvButton.UP; Btn.DOWN -> AtvButton.DOWN
        Btn.LEFT -> AtvButton.LEFT; Btn.RIGHT -> AtvButton.RIGHT
        Btn.OK -> AtvButton.SELECT; Btn.BACK -> AtvButton.MENU
        Btn.HOME -> AtvButton.HOME; Btn.PLAY_PAUSE -> AtvButton.PLAY_PAUSE
        Btn.VOL_UP -> AtvButton.VOLUME_UP; Btn.VOL_DOWN -> AtvButton.VOLUME_DOWN
        else -> null
    }

    override fun down(b: Btn) {
        if (b == Btn.POWER) {
            val btn = if (asleep) AtvButton.WAKE else AtvButton.SLEEP
            asleep = !asleep
            send { client!!.press(btn, true); Thread.sleep(50); client!!.press(btn, false) }
            return
        }
        val m = map(b) ?: return
        send { client!!.press(m, true) }
    }

    override fun up(b: Btn) {
        val m = map(b) ?: return
        send { client!!.press(m, false) }
    }
}

class GoogleController(private val ctx: Context, private val d: Device) : Controller() {
    private var remote: GoogleTvRemote? = null
    private val pending = HashMap<Btn, ScheduledFuture<*>>()
    private val longActive = HashSet<Btn>()
    override val supported = Btn.values().toSet()

    override fun open() {
        val r = GoogleTvRemote(d.host, IdentityStore.get(ctx))
        remote = r
        r.onDisconnected = { onStatus?.invoke("连接已断开，按任意键重连") }
        r.connect()
    }

    override fun isOpen() = remote?.connected == true
    override fun shutdown() { remote?.close(); remote = null }

    private fun code(b: Btn) = when (b) {
        Btn.POWER -> GKey.POWER; Btn.HOME -> GKey.HOME; Btn.BACK -> GKey.BACK
        Btn.UP -> GKey.UP; Btn.DOWN -> GKey.DOWN; Btn.LEFT -> GKey.LEFT; Btn.RIGHT -> GKey.RIGHT
        Btn.OK -> GKey.CENTER; Btn.PLAY_PAUSE -> GKey.PLAY_PAUSE
        Btn.VOL_UP -> GKey.VOLUME_UP; Btn.VOL_DOWN -> GKey.VOLUME_DOWN
        Btn.MUTE -> GKey.MUTE; Btn.MENU -> GKey.SETTINGS
    }

    // Short tap -> SHORT; held > 500 ms -> START_LONG ... END_LONG (like the real remote).
    override fun down(b: Btn) {
        exec.execute {
            pending.remove(b)?.cancel(false)
            pending[b] = exec.schedule({
                pending.remove(b)
                longActive.add(b)
                send { remote!!.key(code(b), GKey.START_LONG) }
            }, 500, TimeUnit.MILLISECONDS)
        }
    }

    override fun up(b: Btn) {
        exec.execute {
            if (longActive.remove(b)) {
                send { remote!!.key(code(b), GKey.END_LONG) }
            } else {
                pending.remove(b)?.cancel(false)
                send { remote!!.key(code(b), GKey.SHORT) }
            }
        }
    }
}
