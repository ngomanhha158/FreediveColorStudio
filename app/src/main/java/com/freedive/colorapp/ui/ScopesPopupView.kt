// ============================================================================
//  TASK 2.4 — SCOPES POPUP VIEW · lop dieu khien gesture cho Floating Scopes
//  Scope duoc VE BANG VULKAN ngay tren swapchain (scopes_popup.frag) — view nay
//  trong suot, chi bat gesture va day cau hinh xuong renderer qua JNI:
//    · Keo tha tu do + SNAP vao 4 goc man hinh khi tha
//    · Pinch 2 ngon de phong to/thu nho (0.10..0.60 chieu ngan man hinh)
//    · SMART OPACITY: 40% khi playback, nhay 100% khi cham slider mau
//    · Nut cycle ben canh man hinh: Vectorscope -> Waveform -> OFF
// ============================================================================
package com.freedive.colorapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.freedive.colorapp.NativeBridge
import kotlin.math.abs

class ScopesPopupView(context: Context) : View(context) {

    companion object {
        const val MODE_OFF = 0
        const val MODE_VECTORSCOPE = 1
        const val MODE_WAVEFORM = 2
        private const val OPACITY_PLAYBACK = 0.40f
        private const val OPACITY_ACTIVE = 1.00f
        private const val ACTIVE_HOLD_MS = 1500L
    }

    private var mode = MODE_OFF
    private var cx = 0.80f          // tam popup, chuan hoa 0..1
    private var cy = 0.16f
    private var size = 0.30f        // canh popup theo chieu ngan man hinh
    private var opacity = OPACITY_PLAYBACK

    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                size = (size * d.scaleFactor).coerceIn(0.10f, 0.60f)
                push(); return true
            }
        })

    /** Nut cycle goi ham nay: Vectorscope -> Waveform -> OFF -> ... */
    fun cycleMode() {
        mode = when (mode) {
            MODE_OFF -> MODE_VECTORSCOPE
            MODE_VECTORSCOPE -> MODE_WAVEFORM
            else -> MODE_OFF
        }
        push()
    }

    /** ColorSliders bao cham slider -> opacity 100%, tu ha ve 40% sau 1.5s */
    fun onColorSliderTouched() {
        opacity = OPACITY_ACTIVE
        push()
        removeCallbacks(dimmer)
        postDelayed(dimmer, ACTIVE_HOLD_MS)
    }
    private val dimmer = Runnable { opacity = OPACITY_PLAYBACK; push() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == MODE_OFF) return false
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insidePopup(e.x, e.y)) return false
                dragging = true; lastX = e.x; lastY = e.y
                onColorSliderTouched()               // cham scope cung lam ro 100%
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                cx = (cx + (e.x - lastX) / width).coerceIn(0.05f, 0.95f)
                cy = (cy + (e.y - lastY) / height).coerceIn(0.05f, 0.95f)
                lastX = e.x; lastY = e.y
                push()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                snapToCorner()
            }
        }
        return dragging
    }

    /** SNAP vao goc gan nhat khi tha tay (spec: Draggable & Snap) */
    private fun snapToCorner() {
        val half = size / 2f
        val margin = 0.03f
        cx = if (cx < 0.5f) half + margin else 1f - half - margin
        cy = if (cy < 0.5f) half + margin else 1f - half - margin
        push()
    }

    private fun insidePopup(x: Float, y: Float): Boolean {
        val side = size * minOf(width, height)
        val px = cx * width; val py = cy * height
        return abs(x - px) < side / 2f && abs(y - py) < side / 2f
    }

    private fun push() = NativeBridge.setScopeConfig(mode, cx, cy, size, opacity)
}
