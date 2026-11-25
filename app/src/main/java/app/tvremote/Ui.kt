package app.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF101114.toInt()
    const val CARD = 0xFF1E2026.toInt()
    const val KEY = 0xFF2A2D35.toInt()
    const val KEY_PRESSED = 0xFF454A57.toInt()
    const val ACCENT = 0xFF3D8BFF.toInt()
    const val TEXT = Color.WHITE
    const val SUB = 0xFF9AA0AC.toInt()
    const val RED = 0xFFE5484D.toInt()

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = radius
    }

    fun Context.text(s: String, size: Float = 16f, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun Context.card(title: String, subtitle: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(14).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            onLongClick?.let { cb -> setOnLongClickListener { cb(); true } }
        }
        box.addView(text(title, 17f, bold = true))
        box.addView(text(subtitle, 13f, SUB))
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
        return box
    }

    fun Activity.ask(title: String, hint: String, numeric: Boolean, onOk: (String) -> Unit, onCancel: () -> Unit = {}) {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.CENTER
            textSize = 24f
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
        input.requestFocus()
    }
}
