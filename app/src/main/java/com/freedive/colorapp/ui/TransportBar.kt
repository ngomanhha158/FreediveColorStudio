// ============================================================================
//  TRANSPORT BAR — phat/dung, thanh tua, buoc tung frame
//  Cho phep dung o DUNG frame can grade roi keo slider mau: MainActivity se goi
//  NativeBridge.redraw() moi lan grade doi khi player dang tam dung.
//  Thanh tua khong bi "giat" khi nguoi dung dang keo (co co isTracking).
// ============================================================================
package com.freedive.colorapp.ui

import android.content.Context
import android.graphics.PorterDuff
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class TransportBar(context: Context) : LinearLayout(context) {

    /** Bam nut phat/dung */
    var onPlayToggle: (() -> Unit)? = null
    /** Keo/nhay thanh tua — tra ve vi tri (us) */
    var onSeek: ((Long) -> Unit)? = null
    /** Buoc frame: -1 lui, +1 toi */
    var onStep: ((Int) -> Unit)? = null
    /** Nhay +/- 1 giay */
    var onJump: ((Long) -> Unit)? = null

    private val playChip: TextView
    private val timeText: TextView
    private val bar: SeekBar
    private var durationUs = 0L
    private var isTracking = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.SURFACE)
        setPadding(Theme.dp(context, 12), Theme.dp(context, 8),
                   Theme.dp(context, 12), Theme.dp(context, 8))

        // ---- Hang 1: thanh tua + dong ho ----
        bar = SeekBar(context).apply {
            max = 1000
            progressDrawable?.setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN)
            thumb?.setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser && durationUs > 0) {
                        timeText.text = clock(p * durationUs / 1000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) { isTracking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    isTracking = false
                    if (durationUs > 0) onSeek?.invoke(sb.progress * durationUs / 1000L)
                }
            })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---- Hang 2: nut dieu khien ----
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playChip = Theme.chip(context, "▶") { onPlayToggle?.invoke() }
        row.addView(playChip, Theme.gapLp(context))
        row.addView(Theme.chip(context, "⏮") { onJump?.invoke(-1_000_000L) }, Theme.gapLp(context))
        row.addView(Theme.chip(context, "◀|") { onStep?.invoke(-1) }, Theme.gapLp(context))
        row.addView(Theme.chip(context, "|▶") { onStep?.invoke(+1) }, Theme.gapLp(context))
        row.addView(Theme.chip(context, "⏭") { onJump?.invoke(+1_000_000L) }, Theme.gapLp(context))

        timeText = TextView(context).apply {
            typeface = Theme.MONO_BOLD
            textSize = 12f
            setTextColor(Theme.TEXT)
            gravity = Gravity.END
            text = "00:00.0 / 00:00.0"
        }
        row.addView(timeText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setDuration(us: Long) {
        durationUs = us
        updateClock(0)
    }

    fun setPosition(us: Long) {
        if (isTracking || durationUs <= 0) return
        bar.progress = ((us.coerceIn(0, durationUs) * 1000L) / durationUs).toInt()
        updateClock(us)
    }

    fun setPlaying(playing: Boolean) {
        playChip.text = if (playing) "⏸" else "▶"
        Theme.setChipOn(context, playChip, playing)
    }

    private fun updateClock(us: Long) {
        timeText.text = "${clock(us)} / ${clock(durationUs)}"
    }

    /** mm:ss.d — du chinh xac de doi soat frame ma van gon */
    private fun clock(us: Long): String {
        val total = us.coerceAtLeast(0) / 1000L          // ms
        val m = total / 60000L
        val s = (total % 60000L) / 1000L
        val d = (total % 1000L) / 100L
        return "%02d:%02d.%d".format(m, s, d)
    }
}
