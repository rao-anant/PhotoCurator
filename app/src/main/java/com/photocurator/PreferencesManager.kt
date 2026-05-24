package com.photocurator

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markMonthDone(year: Int, month: Int) {
        val current = getDoneMonths().toMutableSet()
        current.add(monthKey(year, month))
        prefs.edit().putStringSet(KEY_DONE_MONTHS, current).apply()
    }

    fun unmarkMonthDone(year: Int, month: Int) {
        val current = getDoneMonths().toMutableSet()
        current.remove(monthKey(year, month))
        prefs.edit().putStringSet(KEY_DONE_MONTHS, current).apply()
    }

    fun getDoneMonths(): Set<String> =
        prefs.getStringSet(KEY_DONE_MONTHS, emptySet()) ?: emptySet()

    fun isMonthDone(year: Int, month: Int) = getDoneMonths().contains(monthKey(year, month))

    fun saveSortAscending(ascending: Boolean) {
        prefs.edit().putBoolean(KEY_SORT_ASC, ascending).apply()
    }

    fun isSortAscending(): Boolean = prefs.getBoolean(KEY_SORT_ASC, true)

    fun saveIncludePdf(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PDF, include).apply()
    }

    fun isIncludePdf(): Boolean = prefs.getBoolean(KEY_INCLUDE_PDF, false)

    fun monthKey(year: Int, month: Int): String {
        val m = if (month < 10) "0$month" else month.toString()
        return "$year-$m"
    }

    companion object {
        private const val PREFS_NAME = "photo_curator_prefs"
        private const val KEY_DONE_MONTHS = "done_months"
        private const val KEY_SORT_ASC = "sort_ascending"
        private const val KEY_INCLUDE_PDF = "include_pdf"
    }
}