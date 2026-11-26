package app.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.tvremote.Ui.dp
import app.tvremote.Ui.fitSystemBars
import android.graphics.drawable.LayerDrawable
import app.tvremote.Ui.text

class RemoteActivity : Activity() {
    private lateinit var controller: Controller
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = DeviceStore.get(this, intent.getStringExtra("id") ?: "")
        if (device == null) { finish(); return }
        window.statusBarColor = Ui.BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = Controller.create(this, device)
        controller.onStatus = { s -> runOnUiThread { status.text = s } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        root.fitSystemBars()

        // Header: name + status, power button on the right.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(device.name, 20f, bold = true))
        status = text("连接中…", 13f, Ui.SUB)
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(key("", Btn.POWER, 56, Ui.RED, 22f, power = true))
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // D-pad.
        val padSize = dp(290)
        val pad = FrameLayout(this).apply { background = Ui.rounded(Ui.CARD, 0f, oval = true) }
        fun place(v: View, g: Int) {
            pad.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, g))
        }
        place(key("▲", Btn.UP, 84, Ui.CARD, 26f), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        place(key("▼", Btn.DOWN, 84, Ui.CARD, 26f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        place(key("◀", Btn.LEFT, 84, Ui.CARD, 26f), Gravity.START or Gravity.CENTER_VERTICAL)
        place(key("▶", Btn.RIGHT, 84, Ui.CARD, 26f), Gravity.END or Gravity.CENTER_VERTICAL)
        place(key("OK", Btn.OK, 110, Ui.KEY, 22f), Gravity.CENTER)
        body.addView(pad, LinearLayout.LayoutParams(padSize, padSize).apply { bottomMargin = dp(32) })

        val apple = device.type == TvType.APPLE
        body.addView(row(
            key(if (apple) "‹ 返回" else "← 返回", Btn.BACK, 76, Ui.KEY, 15f),
            key("⌂ 主页", Btn.HOME, 76, Ui.KEY, 15f),
            key("⏯", Btn.PLAY_PAUSE, 76, Ui.KEY, 24f),
        ))
        val volume = mutableListOf(key("音量−", Btn.VOL_DOWN, 76, Ui.KEY, 15f))
        if (Btn.MUTE in controller.supported) volume.add(key("静音", Btn.MUTE, 76, Ui.KEY, 15f))
        volume.add(key("音量+", Btn.VOL_UP, 76, Ui.KEY, 15f))
        body.addView(row(*volume.toTypedArray()), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        val hint = if (apple) "按住「主页」可打开控制中心；手机音量键也能调电视音量"
        else "长按按键等同遥控器长按；手机音量键也能调电视音量"
        body.addView(text(hint, 12f, Ui.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) })

        setContentView(root)
        controller.connect()
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(12); rightMargin = dp(12)
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(label: String, b: Btn, sizeDp: Int, color: Int, textSize: Float, power: Boolean = false): View {
        val bg = Ui.rounded(color, 0f, oval = true)
        return TextView(this).apply {
            text = label
            this.textSize = textSize
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = if (power) LayerDrawable(arrayOf(bg, Ui.PowerGlyph(dp(3).toFloat()))) else bg
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            minWidth = dp(sizeDp); minHeight = dp(sizeDp)
            width = dp(sizeDp); height = dp(sizeDp)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bg.setColor(Ui.KEY_PRESSED)
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        controller.down(b)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        bg.setColor(color)
                        controller.up(b)
                    }
                }
                true
            }
        }
    }

    private fun volumeBtn(code: Int) = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> Btn.VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Btn.VOL_DOWN
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = volumeBtn(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) controller.down(b)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val b = volumeBtn(keyCode) ?: return super.onKeyUp(keyCode, event)
        controller.up(b)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.close()
    }
}
