// ============================================================================
//  DESIGN SYSTEM — "Abyss" · dark pro-tool theme cho Freedive Color Studio
//  Muc tieu: thay toan bo Button/ToggleButton mac dinh cua Android bang chip
//  bo tron ve tay (GradientDrawable + RippleDrawable) — dong nhat, 1 dong chu,
//  KHONG BAO GIO vo dong; so lieu dung font mono de doc chinh xac.
//  Nguyen tac: "Less is More" — thao tac phu gom vao menu ⋮, khong bay het ra.
// ============================================================================
package com.freedive.colorapp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

object Theme {

    // ---- Bang mau -----------------------------------------------------------
    const val BG          = 0xFF07101C.toInt()   // nen sau nhat
    const val SURFACE     = 0xFF0E1A2B.toInt()   // the/panel
    const val SURFACE_HI  = 0xFF16273D.toInt()   // chip trang thai thuong
    const val STROKE      = 0x1FFFFFFF           // vien mo
    const val STROKE_ON   = 0xFF2E9BD6.toInt()   // vien khi bat
    const val ACCENT      = 0xFF2E9BD6.toInt()   // xanh nuoc — hanh dong chinh
    const val ACCENT_DEEP = 0xFF17608A.toInt()
    const val WARN        = 0xFFE0873A.toInt()   // cam — canh bao/keyframe
    const val TEXT        = 0xFFE8F0F8.toInt()
    const val TEXT_DIM    = 0xFF8FA3B8.toInt()   // nhan phu, tieu de nhom
    const val TEXT_MUTED  = 0xFF5E7186.toInt()

    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    // ---- Nen bo tron co ripple ---------------------------------------------
    private fun roundedBg(c: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(c, radiusDp.toFloat())
            setColor(fill)
            setStroke(dp(c, 1), stroke)
        }

    private fun withRipple(c: Context, base: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), base, null)

    // ---- CHIP: nut co ban, luon 1 dong, khong vo chu ------------------------
    /**
     * Chip bam. [checkable] = true thi tu doi mau khi bat/tat.
     * Chu LUON o 1 dong (maxLines=1) — day chinh la loi "CYA N" o ban truoc.
     */
    fun chip(
        c: Context,
        label: String,
        checkable: Boolean = false,
        checked: Boolean = false,
        accentWhenOn: Int = ACCENT,
        onClick: ((TextView) -> Unit)? = null,
    ): TextView = TextView(c).apply {
        text = label
        maxLines = 1
        isSingleLine = true
        ellipsize = null
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        isClickable = true
        isFocusable = true

        var isOn = checked
        fun paint() {
            val fill = if (isOn) ACCENT_DEEP else SURFACE_HI
            val line = if (isOn) accentWhenOn else STROKE
            background = withRipple(c, roundedBg(c, fill, line, 10))
            setTextColor(if (isOn) TEXT else TEXT_DIM)
        }
        paint()
        tag = isOn
        setOnClickListener {
            if (checkable) { isOn = !isOn; tag = isOn; paint() }
            onClick?.invoke(this)
        }
    }

    /** Doc/ghi trang thai bat-tat cua chip tao boi [chip] */
    fun chipIsOn(v: TextView): Boolean = (v.tag as? Boolean) ?: false

    fun setChipOn(c: Context, v: TextView, on: Boolean, accentWhenOn: Int = ACCENT) {
        v.tag = on
        val fill = if (on) ACCENT_DEEP else SURFACE_HI
        val line = if (on) accentWhenOn else STROKE
        v.background = withRipple(c, roundedBg(c, fill, line, 10))
        v.setTextColor(if (on) TEXT else TEXT_DIM)
    }

    /** Nut hanh dong chinh — nen dac mau accent */
    fun primary(c: Context, label: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label
        maxLines = 1
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13))
        background = withRipple(c, roundedBg(c, ACCENT, ACCENT, 12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Nut phu — vien mo, nen trong */
    fun ghost(c: Context, label: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label
        maxLines = 1
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT_DIM)
        setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
        background = withRipple(c, roundedBg(c, SURFACE_HI, STROKE, 12))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Tieu de nhom — chu nho, gian cach chu, mau mo */
    fun sectionLabel(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(TEXT_MUTED)
        letterSpacing = 0.14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(c, 2), dp(c, 12), 0, dp(c, 6))
    }

    /** The chua noi dung — nen surface bo tron */
    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(c, SURFACE, STROKE, 14)
        setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 10))
    }

    /**
     * Dai cuon ngang — giai phap goc re cho viec chu bi vo dong: thay vi ep
     * N nut vao chieu rong man hinh, cho phep cuon.
     */
    fun strip(c: Context): Pair<HorizontalScrollView, LinearLayout> {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sv = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return sv to row
    }

    /** Khoang cach giua cac chip trong mot dai */
    fun gapLp(c: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(c, 8) }

    /** Gia tri so — font mono, canh phai, de doi soat nhanh */
    fun valueText(c: Context): TextView = TextView(c).apply {
        typeface = MONO_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(TEXT)
        gravity = Gravity.END
    }
}
