// ============================================================================
//  NGON NGU — Tieng Viet / English
//  Khong dung res/values-en de tranh phai tach toan bo chuoi ra XML: UI cua app
//  duoc dung bang Kotlin nen cach gon nhat la ham t(vi, en) tai cho.
//  Doi ngon ngu -> luu SharedPreferences -> Activity.recreate().
// ============================================================================
package com.freedive.colorapp.ui

import android.content.Context

object L {
    private const val PREF = "fdc_prefs"
    private const val KEY = "lang"

    /** true = Tieng Viet (mac dinh), false = English */
    var isVi: Boolean = true
        private set

    fun init(c: Context) {
        isVi = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "vi") != "en"
    }

    fun set(c: Context, vietnamese: Boolean) {
        isVi = vietnamese
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (vietnamese) "vi" else "en").apply()
    }

    /** Chon chuoi theo ngon ngu dang bat */
    fun t(vi: String, en: String): String = if (isVi) vi else en

    /** Nhan hien tren nut doi ngon ngu */
    fun switchLabel(): String = if (isVi) "🌐  EN" else "🌐  VI"
}
