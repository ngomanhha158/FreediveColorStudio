// ============================================================================
//  TASK E4 (spec 4.2 + 4.4) — LUT LIBRARY VIEW · thu vien LUT cuon ngang
//  · Nhom theo CATEGORY (thu muc con cua filesDir/luts) — spec 4.4.4.
//  · THUMBNAIL LIVE (spec 4.4.2): frame thu nho cua clip dang mo (Kotlin lay
//    qua MediaMetadataRetriever ~256px) -> NativeBridge.applyLutToBitmap
//    (C++ trilinear) -> moi LUT thay truoc ket qua tren footage THAT.
//  · Cham = chon LUT (loadLutFromPath + cap nhat GradeState.lutPath).
//  · NHAN GIU = menu Rename / Delete (spec 4.4.3).
//  View-based theo QD14 (ban Compose tham chieu: docs/compose-ref/LutLibraryCompose.kt).
// ============================================================================
package com.freedive.colorapp.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.freedive.colorapp.NativeBridge
import kotlin.concurrent.thread

class LutLibraryView(context: Context) : LinearLayout(context) {

    /** MainActivity mo SAF OpenMultipleDocuments */
    var onImportClick: (() -> Unit)? = null
    /** Bao ve MainActivity khi 1 LUT duoc chon (duong dan tuyet doi) */
    var onLutSelected: ((String) -> Unit)? = null

    private val repo = LutRepository(context)
    private val listArea = LinearLayout(context).apply { orientation = VERTICAL }
    private var sourceUri: Uri? = null
    private var baseThumb: Bitmap? = null           // frame thu nho CHUA ap LUT
    private var selectedPath: String? = null
    private val thumbViews = mutableMapOf<String, ImageView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(235, 14, 17, 22))
        setPadding(dp(10), dp(6), dp(10), dp(8))

        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = "LUT LIBRARY"
            setTextColor(Color.rgb(137, 145, 158)); textSize = 10.5f
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(context).apply {
            text = "＋ Nhap .cube"
            setOnClickListener { onImportClick?.invoke() }
        })
        addView(head)
        addView(listArea)
        refresh()
    }

    /** Goi khi doi clip — thumbnail tai theo frame cua clip moi (spec 4.4.2) */
    fun setSourceClip(uri: Uri?) {
        sourceUri = uri
        baseThumb = null
        rebuildThumbnails()
    }

    /** Goi sau khi import xong de ve lai danh sach */
    fun refresh() {
        listArea.removeAllViews()
        thumbViews.clear()
        val groups = repo.list()
        if (groups.isEmpty()) {
            listArea.addView(TextView(context).apply {
                text = "Chua co LUT nao — bam ＋ de nhap file .cube (chon duoc nhieu file)"
                setTextColor(Color.rgb(90, 100, 114)); textSize = 11f
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        groups.forEach { (category, entries) ->
            listArea.addView(TextView(context).apply {
                text = "▸ $category"
                setTextColor(Color.rgb(127, 178, 240)); textSize = 10f
                setPadding(0, dp(6), 0, dp(2))
            })
            val row = LinearLayout(context)
            entries.forEach { row.addView(lutCard(it), LayoutParams(dp(96), LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }) }
            listArea.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        rebuildThumbnails()
    }

    // ------------------------------------------------------------------ card
    private fun lutCard(entry: LutEntry): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(
                if (entry.file.absolutePath == selectedPath) Color.argb(70, 57, 135, 229)
                else Color.argb(50, 255, 255, 255))
            setPadding(dp(3), dp(3), dp(3), dp(4))
        }
        val img = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(10, 13, 18))
        }
        thumbViews[entry.file.absolutePath] = img
        card.addView(img)
        card.addView(TextView(context).apply {
            text = entry.name
            setTextColor(Color.WHITE); textSize = 9.5f; maxLines = 1
        })
        card.setOnClickListener {
            if (NativeBridge.loadLutFromPath(entry.file.absolutePath)) {
                selectedPath = entry.file.absolutePath
                onLutSelected?.invoke(entry.file.absolutePath)
                refresh()
            }
        }
        // E4 — spec 4.4.3: nhan giu -> menu Rename / Delete
        card.setOnLongClickListener {
            PopupMenu(context, card).apply {
                menu.add("Doi ten")
                menu.add("Xoa")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Doi ten" -> promptRename(entry)
                        "Xoa" -> { repo.delete(entry); refresh() }
                    }
                    true
                }
            }.show()
            true
        }
        return card
    }

    private fun promptRename(entry: LutEntry) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(entry.name)
        }
        AlertDialog.Builder(context)
            .setTitle("Doi ten LUT")
            .setView(input)
            .setPositiveButton("Luu") { _, _ ->
                repo.rename(entry, input.text.toString())
                refresh()
            }
            .setNegativeButton("Huy", null)
            .show()
    }

    // ------------------------------------------------- live thumbnails (4.4.2)
    /** Lay frame thu nho tu clip dang mo, roi ap TUNG LUT bang C++ (thread nen) */
    private fun rebuildThumbnails() {
        val paths = thumbViews.keys.toList()
        if (paths.isEmpty()) return
        thread(name = "fdc-lut-thumbs") {
            val base = baseThumb ?: extractBaseThumb()?.also { baseThumb = it }
            paths.forEach { path ->
                val bmp = base?.copy(Bitmap.Config.ARGB_8888, /*mutable=*/true) ?: return@forEach
                val ok = NativeBridge.applyLutToBitmap(bmp, path)
                post { thumbViews[path]?.setImageBitmap(if (ok) bmp else base) }
            }
        }
    }

    /** Frame ~256px tu clip hien tai; null -> thumbnail xam trung tinh */
    private fun extractBaseThumb(): Bitmap? {
        val uri = sourceUri ?: return neutralThumb()
        return runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            f?.let { Bitmap.createScaledBitmap(it, 256, 256 * it.height / it.width, true) }
                ?.copy(Bitmap.Config.ARGB_8888, true)
        }.getOrNull() ?: neutralThumb()
    }

    /** Gradient xanh bien trung tinh khi chua co clip — LUT van the hien duoc mau */
    private fun neutralThumb(): Bitmap {
        val w = 256; val h = 144
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val t = y.toFloat() / h
            bmp.setPixel(x, y, Color.rgb(
                (40 - 14 * t).toInt(), (120 - 52 * t).toInt(), (128 - 44 * t).toInt()))
        }
        return bmp
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
