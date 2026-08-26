// ============================================================================
//  FABLE TASK 1.2 — VIDEO PLAYER VIEW · SurfaceView gan Vulkan swapchain
//  Vong doi surface -> NativeBridge; renderer ve qua swapchain fp16-pipeline.
// ============================================================================
package com.freedive.colorapp.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.freedive.colorapp.NativeBridge

class VideoPlayerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object { private const val TAG = "FDC/Player" }

    /** Ty le khung hinh video (16:9 mac dinh DJI Action) de do view chuan */
    var videoAspect: Float = 16f / 9f
        set(value) { field = value; requestLayout() }

    var onSurfaceReady: (() -> Unit)? = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val ok = NativeBridge.surfaceCreated(holder.surface, context.assets)
        if (!ok) Log.e(TAG, "Khoi tao Vulkan that bai: ${NativeBridge.lastError()}")
        else onSurfaceReady?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeBridge.surfaceResized(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.surfaceDestroyed()
    }

    // Do view theo ty le video — khong meo hinh khi xoay may
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val byWidth = (w / videoAspect).toInt()
        if (byWidth <= h) setMeasuredDimension(w, byWidth)
        else setMeasuredDimension((h * videoAspect).toInt(), h)
    }
}
