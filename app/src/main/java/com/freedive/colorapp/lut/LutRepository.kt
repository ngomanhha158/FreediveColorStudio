// ============================================================================
//  TASK E4 (spec 4.2 + 4.4) — LUT REPOSITORY · kho .cube noi bo cua app
//  filesDir/luts/<category>/<ten>.cube — moi file copy AN TOAN tu SAF URI vao
//  bo nho trong (ton tai qua cac phien — spec 4.2.2). Thu muc con = category
//  (spec 4.4.4: "Deep Water", "Shallow Reef"...); file dat truc tiep trong
//  luts/ thuoc category mac dinh "Da nhap".
//  Rename/Delete (spec 4.4.3) thao tac thang len file he thong.
// ============================================================================
package com.freedive.colorapp.lut

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class LutEntry(val file: File, val category: String) {
    val name: String get() = file.nameWithoutExtension
}

class LutRepository(private val context: Context) {

    companion object {
        const val DEFAULT_CATEGORY = "Da nhap"
        private val SAFE_NAME = Regex("[^A-Za-z0-9 _.\\-()\\[\\]]")
    }

    private val root: File get() = File(context.filesDir, "luts").apply { mkdirs() }

    /** Danh sach LUT theo category (thu muc con truoc, roi file goc), sap xep ten */
    fun list(): Map<String, List<LutEntry>> {
        val out = linkedMapOf<String, MutableList<LutEntry>>()
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (d in dirs) {
            val files = d.listFiles { f -> f.isFile && f.extension.equals("cube", true) }
                ?.sortedBy { it.name } ?: continue
            if (files.isNotEmpty()) out[d.name] = files.map { LutEntry(it, d.name) }.toMutableList()
        }
        val loose = root.listFiles { f -> f.isFile && f.extension.equals("cube", true) }
            ?.sortedBy { it.name } ?: emptyList()
        if (loose.isNotEmpty())
            out.getOrPut(DEFAULT_CATEGORY) { mutableListOf() } +=
                loose.map { LutEntry(it, DEFAULT_CATEGORY) }
        return out
    }

    /**
     * E4 — BATCH IMPORT (spec 4.4.1): copy tung URI (OpenMultipleDocuments) vao
     * kho noi bo. Ten lay tu DISPLAY_NAME (lam sach ky tu la), tranh trung bang
     * hau to (2), (3)... Tra ve danh sach file da nhap thanh cong.
     */
    fun import(uris: List<Uri>, category: String? = null): List<File> {
        val dir = if (category.isNullOrBlank()) root
                  else File(root, SAFE_NAME.replace(category, "_")).apply { mkdirs() }
        val done = mutableListOf<File>()
        for (uri in uris) {
            val display = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.cube"
            var base = SAFE_NAME.replace(display.removeSuffix(".cube").removeSuffix(".CUBE"), "_")
                .trim().ifEmpty { "lut_${System.currentTimeMillis()}" }
            var target = File(dir, "$base.cube")
            var n = 2
            while (target.exists()) { target = File(dir, "$base (${n++}).cube") }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching
                // Kiem tra so bo: file .cube phai co dong LUT_3D_SIZE
                val head = target.bufferedReader().use { r ->
                    generateSequence { r.readLine() }.take(64).joinToString("\n")
                }
                if (!head.contains("LUT_3D_SIZE")) { target.delete(); return@runCatching }
                done += target
            }.onFailure { target.delete() }
        }
        return done
    }

    /** E4 — RENAME (spec 4.4.3). Tra ve file moi, hoac null neu trung ten/loi. */
    fun rename(entry: LutEntry, newName: String): File? {
        val clean = SAFE_NAME.replace(newName, "_").trim()
        if (clean.isEmpty()) return null
        val target = File(entry.file.parentFile, "$clean.cube")
        if (target.exists()) return null
        return if (entry.file.renameTo(target)) target else null
    }

    /** E4 — DELETE (spec 4.4.3). Xoa file; thu muc category rong duoc don luon. */
    fun delete(entry: LutEntry): Boolean {
        val ok = entry.file.delete()
        entry.file.parentFile?.takeIf { it != root && it.listFiles()?.isEmpty() == true }?.delete()
        return ok
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                          null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}
