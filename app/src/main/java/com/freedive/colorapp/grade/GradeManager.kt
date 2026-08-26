// ============================================================================
//  TASK 3.3/3.4 — GRADE MANAGER · Copy Attributes / Paste to All
//  - Moi clip (Uri) giu mot GradeState rieng.
//  - Copy: serialize state clip dang chon -> filesDir/grade_clipboard.json.
//  - Paste to All: ap clipboard cho danh sach clip duoc chon (danh dau trong
//    gallery); clip dang phat ap ngay xuong renderer, cac clip khac ap khi mo.
//  Tensor NPU batch render dung chinh map nay o Tuan 4 (hang doi xuat file).
// ============================================================================
package com.freedive.colorapp.grade

import android.content.Context
import android.net.Uri
import java.io.File

class GradeManager(context: Context) {

    private val clipboardFile = File(context.filesDir, "grade_clipboard.json")
    private val grades = mutableMapOf<Uri, GradeState>()

    /** State cua clip (tao moi neu chua co) */
    fun stateFor(uri: Uri): GradeState = grades.getOrPut(uri) { GradeState() }

    fun put(uri: Uri, state: GradeState) { grades[uri] = state }

    /** Task 5.2 — snapshot/khoi phuc cho DraftStore */
    fun snapshot(): Map<Uri, GradeState> = grades.toMap()
    fun restore(saved: Map<Uri, GradeState>) { grades.clear(); grades.putAll(saved) }

    /** COPY ATTRIBUTES — luu toan bo 3 layer cua clip ra file JSON cuc bo */
    fun copyAttributes(uri: Uri): Boolean = runCatching {
        stateFor(uri).saveTo(clipboardFile)
    }.isSuccess

    fun hasClipboard(): Boolean = clipboardFile.exists()

    /**
     * PASTE TO ALL — ap clipboard cho cac clip da chon.
     * @return so clip da ap; clip dang phat (currentUri) duoc day ngay xuong GPU.
     */
    fun pasteToAll(targets: Collection<Uri>, currentUri: Uri?): Int {
        val src = GradeState.loadFrom(clipboardFile) ?: return 0
        var n = 0
        for (uri in targets) {
            // Moi clip nhan BAN SAO doc lap (deep copy qua JSON round-trip)
            grades[uri] = GradeState.fromJson(src.toJson())
            n++
        }
        if (currentUri != null && currentUri in targets) grades[currentUri]?.applyTo()
        return n
    }
}
