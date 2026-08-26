// ============================================================================
//  TASK 4.2 + 4.3 — BATCH EXPORTER · hang doi xuat nhieu clip + notification
//  Chay tren thread nen: voi tung clip trong hang doi -> ap GradeState rieng
//  cua clip (map cua GradeManager tuan 3) -> ExportEngine.exportClip.
//  VRAM/RAM duoc tra sau TUNG clip (endExport -> clearAhbCache) — dung yeu cau
//  "giai phong ngay sau khi render xong tung clip de may khong bi nong".
//  Notification: kenh "fdc_export", progress bar cap nhat theo clip + %,
//  bao hoan thanh khi xong ca batch (Task 4.3).
// ============================================================================
package com.freedive.colorapp.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.freedive.colorapp.grade.GradeManager
import java.io.File
import kotlin.concurrent.thread

class BatchExporter(
    private val context: Context,
    private val gradeManager: GradeManager,
) {
    companion object {
        private const val CHANNEL = "fdc_export"
        private const val NOTIF_ID = 41
    }

    /** E1 — cau hinh xuat dung chung cho ca batch (MainActivity gan tu options bar) */
    var config: ExportConfig = ExportConfig()

    /** (clipIndex, clipCount, percentOfClip) — cap nhat UI; goi tren thread nen */
    var onProgress: ((Int, Int, Int) -> Unit)? = null
    /** Danh sach file da xuat khi xong batch */
    var onFinished: ((List<File>) -> Unit)? = null

    @Volatile var running = false
        private set

    private val notifier =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        notifier.createNotificationChannel(
            NotificationChannel(CHANNEL, "Xuat video", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Xuat tuan tu cac clip (thread nen). Preview phai dung truoc khi goi. */
    fun exportAll(clips: List<Uri>) {
        if (running || clips.isEmpty()) return
        running = true
        thread(name = "fdc-batch-export") {
            val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            val engine = ExportEngine(context)
            val results = mutableListOf<File>()
            clips.forEachIndexed { i, uri ->
                // Ap grade RIENG cua clip nay truoc khi xuat (nen tang batch tuan 3)
                gradeManager.stateFor(uri).applyTo()
                engine.onProgress = { pct ->
                    onProgress?.invoke(i + 1, clips.size, pct)
                    notify("Dang xuat clip ${i + 1}/${clips.size}", pct, ongoing = true)
                }
                val out = File(outDir, "FDC_${System.currentTimeMillis()}_${i + 1}.mp4")
                engine.exportClip(uri, out, config)?.let { results += it }
                // VRAM da duoc tra trong endExport cua tung clip (Task 4.2)
            }
            notify("Xuat xong ${results.size}/${clips.size} clip", 100, ongoing = false)
            running = false
            onFinished?.invoke(results)
        }
    }

    private fun notify(text: String, pct: Int, ongoing: Boolean) {
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Freediving Color")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
        notifier.notify(NOTIF_ID, n)
    }
}
