package com.anant.mediacurator

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

    fun setDoneMonths(months: Set<String>) {
        prefs.edit().putStringSet(KEY_DONE_MONTHS, months).apply()
    }

    fun isMonthDone(year: Int, month: Int) = getDoneMonths().contains(monthKey(year, month))

    fun saveSortMode(mode: SortMode) {
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    fun getSortMode(): SortMode {
        val saved = prefs.getString(KEY_SORT_MODE, null)
        if (saved != null) {
            return try { SortMode.valueOf(saved) } catch (e: Exception) { SortMode.SIZE_ABSOLUTE } // SIZE_LARGEST → SIZE_ABSOLUTE
        }
        // Migrate from old boolean pref (default is DATE_OLDEST)
        return if (prefs.getBoolean(KEY_SORT_ASC, true)) SortMode.DATE_OLDEST else SortMode.DATE_NEWEST
    }

    fun saveIncludePhoto(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PHOTO, include).apply()
    }

    fun isIncludePhoto(): Boolean = prefs.getBoolean(KEY_INCLUDE_PHOTO, true)

    fun saveIncludeVideo(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_VIDEO, include).apply()
    }

    fun isIncludeVideo(): Boolean = prefs.getBoolean(KEY_INCLUDE_VIDEO, true)

    fun saveIncludePdf(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PDF, include).apply()
    }

    fun isIncludePdf(): Boolean = prefs.getBoolean(KEY_INCLUDE_PDF, true)

    fun monthKey(year: Int, month: Int): String {
        val m = if (month < 10) "0$month" else month.toString()
        return "$year-$m"
    }

    companion object {
        private const val PREFS_NAME = "photo_curator_prefs"
        private const val KEY_DONE_MONTHS = "done_months"
        private const val KEY_SORT_ASC = "sort_ascending"   // kept for migration read
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_INCLUDE_PHOTO = "include_photo"
        private const val KEY_INCLUDE_VIDEO = "include_video"
        private const val KEY_INCLUDE_PDF = "include_pdf"
    }
}