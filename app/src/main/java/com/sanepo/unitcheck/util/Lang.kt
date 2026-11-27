package com.sanepo.unitcheck.util

import android.content.Context
import java.util.Locale

object Lang {
    private const val PREF_NAME = "sanepo_unitcheck_prefs"
    private const val KEY_LANG = "ui_lang"
    const val LANG_ID = "id"
    const val LANG_EN = "en"

    @Volatile
    private var cachedLang: String? = null

    fun current(context: Context): String {
        cachedLang?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)
        val resolved = if (saved == LANG_ID || saved == LANG_EN) {
            saved
        } else {
            val sysLang = Locale.getDefault().language.lowercase(Locale.US)
            if (sysLang == "in" || sysLang == "id") LANG_ID else LANG_EN
        }
        cachedLang = resolved
        return resolved
    }

    fun isIndonesian(context: Context): Boolean = current(context) == LANG_ID

    fun toggle(context: Context): String {
        val next = if (isIndonesian(context)) LANG_EN else LANG_ID
        set(context, next)
        return next
    }

    fun set(context: Context, lang: String) {
        val normalized = if (lang == LANG_ID) LANG_ID else LANG_EN
        cachedLang = normalized
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, normalized)
            .apply()
    }

    fun t(context: Context, idText: String, enText: String): String {
        return if (isIndonesian(context)) idText else enText
    }
}
