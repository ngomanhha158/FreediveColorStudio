// ============================================================================
//  TASK 5.2 — DRAFT STORE · tu dong luu/khoi phuc phien lam viec
//  Luu: danh sach clip trong gallery + GradeState RIENG cua tung clip vao
//  filesDir/draft.json. Ghi DEBOUNCE 800ms sau moi thay doi (keo slider lien
//  tuc khong ghi dia lien tuc) + ghi ngay o onPause. Mo app (ke ca sau khi bi
//  he thong kill) -> nap lai nguyen trang.
// ============================================================================
package com.freedive.colorapp.grade

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DraftStore(context: Context) {

    private val file = File(context.filesDir, "draft.json")
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    /** Ghi debounce — goi thoai mai moi khi grade/gallery doi */
    fun scheduleSave(clips: List<Uri>, grades: Map<Uri, GradeState>) {
        pendingSave?.let { handler.removeCallbacks(it) }
        val snapshotClips = clips.toList()
        val snapshotGrades = grades.mapValues { GradeState.fromJson(it.value.toJson()) }
        val r = Runnable { saveNow(snapshotClips, snapshotGrades) }
        pendingSave = r
        handler.postDelayed(r, 800)
    }

    /** Ghi ngay (onPause / truoc khi export) */
    fun saveNow(clips: List<Uri>, grades: Map<Uri, GradeState>) {
        runCatching {
            val o = JSONObject()
            o.put("schema", 1)
            o.put("clips", JSONArray().apply { clips.forEach { put(it.toString()) } })
            o.put("grades", JSONObject().apply {
                grades.forEach { (uri, g) -> put(uri.toString(), g.toJson()) }
            })
            file.writeText(o.toString())
        }
    }

    /** Khoi phuc draft: (danh sach clip, map grade) — null neu chua co/loi */
    fun load(): Pair<List<Uri>, Map<Uri, GradeState>>? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val clips = mutableListOf<Uri>()
        o.optJSONArray("clips")?.let { arr ->
            for (i in 0 until arr.length()) clips += Uri.parse(arr.getString(i))
        }
        val grades = mutableMapOf<Uri, GradeState>()
        o.optJSONObject("grades")?.let { g ->
            g.keys().forEach { k -> grades[Uri.parse(k)] = GradeState.fromJson(g.getJSONObject(k)) }
        }
        Pair(clips, grades as Map<Uri, GradeState>)
    }.getOrNull()

    fun clear() { runCatching { file.delete() } }
}
