package app.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.tvremote.Ui.ask
import app.tvremote.Ui.card
import app.tvremote.Ui.dp
import app.tvremote.Ui.text
import app.tvremote.core.CompanionClient
import app.tvremote.core.GoogleTvPairing
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var savedList: LinearLayout
    private lateinit var foundList: LinearLayout
    private val found = LinkedHashMap<String, Found>()
    private var discovery: Discovery? = null
    private var progress: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        root.addView(text("电视遥控器", 28f, bold = true))
        root.addView(text("手机和电视需连接同一个 Wi-Fi", 13f, Ui.SUB).apply { setPadding(0, dp(4), 0, dp(18)) })

        root.addView(text("我的电视", 15f, Ui.SUB).apply { setPadding(0, 0, 0, dp(8)) })
        savedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedList)

        root.addView(text("附近发现", 15f, Ui.SUB).apply { setPadding(0, dp(14), 0, dp(8)) })
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(foundList)

        root.addView(Button(this).apply {
            text = "手动输入 IP 添加"
            setTextColor(Ui.TEXT)
            background = Ui.rounded(Ui.KEY, dp(12).toFloat())
            setOnClickListener { manualAdd() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        discovery = Discovery(this) { f -> onFound(f) }.also { it.start() }
    }

    override fun onPause() {
        super.onPause()
        discovery?.stop()
        discovery = null
    }

    private fun typeLabel(t: TvType) = if (t == TvType.APPLE) "Apple TV" else "小米 / Google TV"

    private fun onFound(f: Found) {
        // A saved TV may have a new IP address: update it silently.
        val saved = DeviceStore.all(this).firstOrNull { it.type == f.type && it.name == f.name }
        if (saved != null) {
            if (saved.host != f.host || saved.port != f.port) {
                saved.host = f.host; saved.port = f.port
                DeviceStore.save(this, saved)
            }
            return
        }
        found["${f.type}/${f.name}"] = f
        refresh()
    }

    private fun refresh() {
        savedList.removeAllViews()
        val devices = DeviceStore.all(this)
        if (devices.isEmpty()) savedList.addView(text("还没有添加电视", 14f, Ui.SUB))
        for (d in devices) {
            val sub = typeLabel(d.type) + " · " + d.host + if (d.paired) "" else " · 未配对"
            savedList.addView(card(d.name, sub, { open(d) }, { confirmDelete(d) }))
        }
        foundList.removeAllViews()
        val names = devices.map { "${it.type}/${it.name}" }.toSet()
        val list = found.filterKeys { it !in names }.values
        if (list.isEmpty()) foundList.addView(text("正在搜索…", 14f, Ui.SUB))
        for (f in list) {
            foundList.addView(card(f.name, typeLabel(f.type) + " · " + f.host + " · 点击配对", {
                val d = Device.new(f.type, f.name, f.host, f.port)
                DeviceStore.save(this, d)
                refresh()
                pair(d)
            }))
        }
    }

    private fun confirmDelete(d: Device) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("删除「${d.name}」？")
            .setMessage("删除后需要重新配对。")
            .setPositiveButton("删除") { _, _ -> DeviceStore.delete(this, d.id); refresh() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manualAdd() {
        val types = arrayOf("Apple TV", "小米 / Google TV")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("电视类型")
            .setItems(types) { _, which ->
                val type = if (which == 0) TvType.APPLE else TvType.GOOGLE
                val hint = if (type == TvType.APPLE) "例如 192.168.1.20（端口默认 49153）" else "例如 192.168.1.30"
                ask("输入电视的 IP 地址", hint, false, { raw ->
                    val s = raw.trim()
                    if (s.isEmpty()) return@ask
                    val host = s.substringBefore(':')
                    val port = s.substringAfter(':', "").toIntOrNull() ?: if (type == TvType.APPLE) 49153 else 6466
                    val d = Device.new(type, "${types[which]} ($host)", host, port)
                    DeviceStore.save(this, d)
                    refresh()
                    pair(d)
                })
            }
            .show()
    }

    private fun open(d: Device) {
        if (!d.paired) { pair(d); return }
        startActivity(Intent(this, RemoteActivity::class.java).putExtra("id", d.id))
    }

    private fun busy(msg: String?) {
        progress?.dismiss()
        progress = msg?.let { ProgressDialog.show(this, null, it, true, false) }
    }

    private fun fail(e: Throwable) = runOnUiThread {
        busy(null)
        Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    private fun pair(d: Device) = if (d.type == TvType.APPLE) pairApple(d) else pairGoogle(d)

    private fun paired(d: Device, creds: String) = runOnUiThread {
        busy(null)
        d.credentials = creds
        DeviceStore.save(this, d)
        Toast.makeText(this, "配对成功", Toast.LENGTH_SHORT).show()
        refresh()
        open(d)
    }

    private fun pairApple(d: Device) {
        busy("正在连接 ${d.name}…")
        thread {
            val c = CompanionClient(d.host, d.port, BcCrypto)
            try {
                c.startPairing()
            } catch (e: Exception) { c.close(); fail(e); return@thread }
            runOnUiThread {
                busy(null)
                ask("输入 Apple TV 屏幕上的 4 位 PIN", "PIN", true, { pin ->
                    busy("正在配对…")
                    thread {
                        try {
                            val creds = c.finishPairing(pin, "Android 遥控器")
                            c.close()
                            paired(d, creds.serialize())
                        } catch (e: Exception) { c.close(); fail(e) }
                    }
                }, { c.close() })
            }
        }
    }

    private fun pairGoogle(d: Device) {
        busy("正在连接 ${d.name}…")
        thread {
            val p: GoogleTvPairing
            try {
                p = GoogleTvPairing(d.host, IdentityStore.get(this))
                p.start("Android 遥控器")
            } catch (e: Exception) { fail(e); return@thread }
            runOnUiThread {
                busy(null)
                ask("输入电视屏幕上的 6 位配对码", "例如 1A2B3C", false, { code ->
                    busy("正在配对…")
                    thread {
                        try {
                            p.finish(code)
                            paired(d, "ok")
                        } catch (e: Exception) { p.close(); fail(e) }
                    }
                }, { p.close() })
            }
        }
    }
}
