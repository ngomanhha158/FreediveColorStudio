// ============================================================================
//  TASK 3.2 — CLIP GALLERY · thu vien/timeline mini cac clip da import
//  Cuon ngang, thumbnail lay tu frame dau (MediaMetadataRetriever, nen offload
//  sang thread rieng). Cham = chon phat + ap grade cua clip do; nhan giu =
//  danh dau chon nhieu clip cho "Paste to All".
// ============================================================================
package com.freedive.colorapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout

class ClipGalleryView(context: Context) : HorizontalScrollView(context) {

    /** Cham 1 clip -> phat + ap grade */
    var onClipSelected: ((Uri) -> Unit)? = null
    /** Thay doi tap chon multi-select (Paste to All) */
    var onSelectionChanged: ((Set<Uri>) -> Unit)? = null

    private val row = LinearLayout(context)
    private val clips = mutableListOf<Uri>()
    private val selected = linkedSetOf<Uri>()
    private val thumbThread = HandlerThread("fdc-thumbs").apply { start() }
    private val thumbHandler = Handler(thumbThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        row.orientation = LinearLayout.HORIZONTAL
        addView(row)
        setBackgroundColor(Color.argb(235, 12, 14, 18))
    }

    fun addClip(uri: Uri) {
        if (uri in clips) return
        clips += uri
        val iv = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(60)).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
            setOnClickListener { onClipSelected?.invoke(uri) }
            setOnLongClickListener { toggleSelect(uri, this); true }
        }
        row.addView(iv)
        // Thumbnail load ngam — khong chan UI
        thumbHandler.post {
            val bmp: Bitmap? = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0)
                }
            }.getOrNull()
            if (bmp != null) mainHandler.post { iv.setImageBitmap(bmp) }
        }
    }

    fun allClips(): List<Uri> = clips.toList()
    fun selectedClips(): Set<Uri> = selected.toSet()

    private fun toggleSelect(uri: Uri, iv: ImageView) {
        if (!selected.remove(uri)) selected += uri
        // Vien vang = dang duoc chon cho Paste to All
        iv.setPadding(dp(3), dp(3), dp(3), dp(3))
        iv.setBackgroundColor(if (uri in selected) Color.rgb(250, 178, 25) else Color.DKGRAY)
        onSelectionChanged?.invoke(selected.toSet())
    }

    fun releaseThreads() = thumbThread.quitSafely()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
