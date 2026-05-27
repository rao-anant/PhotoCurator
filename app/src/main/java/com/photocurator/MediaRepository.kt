package com.anant.mediacurator

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class MediaRepository(private val context: Context) {
    private val prefs = PreferencesManager(context)
    
    private val labelFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val filenameDateRegex = Regex("(\\d{4})[_-]?(\\d{2})[_-]?(\\d{2})")
    private val yearOnlyRegex = Regex("(19|20)\\d{2}")

    /**
     * Fetches all media items (Images, Videos, and PDFs).
     */
    fun fetchAllMedia(): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()
        
        // Use "external" volume for broad search.
        val volume = "external"

        // 1. Fetch Images
        fetchFromCollection(
            MediaStore.Images.Media.getContentUri(volume),
            MediaType.IMAGE,
            mediaList
        )

        // 2. Fetch Videos
        fetchFromCollection(
            MediaStore.Video.Media.getContentUri(volume),
            MediaType.VIDEO,
            mediaList
        )

        // 3. Fetch PDFs (from Files and Downloads)
        fetchPdfs(volume, mediaList)

        // Deduplicate based on display name and size to handle files indexed in multiple collections.
        // This is more stable than URI which depends on the collection view (Files vs Images).
        val finalResult = mediaList.distinctBy { "${it.displayName}_${it.size}" }
        
        Log.d("MediaRepository", "Fetched total: ${finalResult.size} items. " +
                "Images: ${finalResult.count { it.type == MediaType.IMAGE }}, " +
                "Videos: ${finalResult.count { it.type == MediaType.VIDEO }}, " +
                "PDFs: ${finalResult.count { it.type == MediaType.PDF }}")
        
        return finalResult
    }

    private fun fetchFromCollection(
        collectionUri: Uri,
        type: MediaType,
        mediaList: MutableList<MediaItem>
    ) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            "datetaken"
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.DURATION)
        }

        try {
            context.contentResolver.query(collectionUri, projection.toTypedArray(), "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val daCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dmCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dtCol = cursor.getColumnIndex("datetaken")
                val durCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val size = cursor.getLong(sizeCol)
                    val dt = if (dtCol != -1) cursor.getLong(dtCol) else 0L
                    val da = cursor.getLong(daCol) * 1000
                    val dm = cursor.getLong(dmCol) * 1000
                    val dur = if (durCol != -1) cursor.getLong(durCol) else 0L
                    
                    val bestDate = resolveBestDate(name, dt, dm, da)
                    val uri = ContentUris.withAppendedId(collectionUri, id).toString()
                    
                    mediaList.add(MediaItem(id, uri, "external", bestDate, name, size, type, dur))
                }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Error fetching $type", e)
        }
    }

    private fun fetchPdfs(volume: String, mediaList: MutableList<MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        // Try both MediaStore.Downloads and MediaStore.Files
        val filesCollectionUri = MediaStore.Files.getContentUri(volume)
        val collections = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Downloads.getContentUri(volume))
        }
        collections.add(filesCollectionUri)

        val selection = "${MediaStore.MediaColumns.SIZE} > 0 AND (" +
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE '%pdf%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.pdf' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.PDF')"

        for (collectionUri in collections) {
            // We need to verify file accessibility only for the Files collection.
            // PDFs deleted via their Downloads URI leave behind orphaned Files entries
            // that persist in the MediaStore database across app reinstalls.
            val isFilesCollection = (collectionUri == filesCollectionUri)
            try {
                context.contentResolver.query(collectionUri, projection, selection, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val daCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dmCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: ""
                        val mime = cursor.getString(mimeCol) ?: ""

                        if (!name.endsWith(".pdf", true) && !mime.contains("pdf", true)) {
                            continue
                        }

                        val id = cursor.getLong(idCol)
                        val size = cursor.getLong(sizeCol)
                        val da = cursor.getLong(daCol) * 1000
                        val dm = cursor.getLong(dmCol) * 1000

                        val fileUri = ContentUris.withAppendedId(collectionUri, id)

                        // For MediaStore.Files entries only: verify the file is actually
                        // accessible.  Stale orphan rows (left after deleting via a
                        // Downloads URI) will throw here and we silently skip them.
                        // This check is intentionally skipped for the Downloads collection
                        // because Downloads entries are always kept in sync with the file.
                        if (isFilesCollection) {
                            try {
                                context.contentResolver.openFileDescriptor(fileUri, "r")?.close()
                            } catch (_: Exception) {
                                continue  // file no longer exists — skip stale entry
                            }
                        }

                        val bestDate = resolvePdfDate(name, da, dm)
                        mediaList.add(MediaItem(id, fileUri.toString(), "external", bestDate, name, size, MediaType.PDF))
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "Error fetching PDFs from $collectionUri", e)
            }
        }
    }

    fun processAndGroupMedia(
        rawMedia: List<MediaItem>,
        sortMode: SortMode,
        doneMonthKeys: Set<String>
    ): Pair<List<MonthGroup>, List<MonthGroup>> {
        // Sort items before grouping
        val sortedList = when (sortMode) {
            SortMode.DATE_OLDEST       -> rawMedia.sortedWith(compareBy({ it.dateTaken }, { it.id }))
            SortMode.DATE_NEWEST       -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
            SortMode.SIZE_ABSOLUTE     -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken })
            SortMode.SIZE_WITHIN_MONTH -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken })
            // Items within each month are shown newest-first; months are re-ordered after grouping.
            SortMode.COUNT_PER_MONTH   -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
        }

        val visibleGroups = mutableListOf<MonthGroup>()
        val doneGroups = mutableListOf<MonthGroup>()
        val groupMap = mutableMapOf<String, MonthGroup>()
        val calendar = Calendar.getInstance()

        for (item in sortedList) {
            calendar.timeInMillis = item.dateTaken
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val key = prefs.monthKey(year, month)

            var group = groupMap[key]
            if (group == null) {
                val label = labelFormat.format(calendar.time)
                group = MonthGroup(year, month, key, label, mutableListOf())
                groupMap[key] = group
                if (doneMonthKeys.contains(key)) doneGroups.add(group) else visibleGroups.add(group)
            }
            group.items.add(item)
        }

        // Post-grouping month-level re-sorts:
        // SIZE_ABSOLUTE     — month order already follows the largest file encountered; no re-sort.
        // SIZE_WITHIN_MONTH — items are size-sorted; restore newest-month-first chronological order.
        // COUNT_PER_MONTH   — sort months so the one with the most items appears first.
        when (sortMode) {
            SortMode.SIZE_WITHIN_MONTH -> visibleGroups.sortByDescending { g -> g.year * 100 + g.month }
            SortMode.COUNT_PER_MONTH   -> visibleGroups.sortByDescending { it.items.size }
            else -> { /* order already correct */ }
        }

        return Pair(visibleGroups, doneGroups)
    }

    private fun resolveBestDate(name: String, dt: Long, dm: Long, da: Long): Long {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        // A timestamp is only "reasonable" if it's in the past and from a plausible year (1980–now).
        fun isReasonable(ts: Long): Boolean {
            if (ts <= 1_000_000L || ts > now) return false
            val year = Calendar.getInstance().also { it.timeInMillis = ts }.get(Calendar.YEAR)
            return year in 1980..currentYear
        }

        // 1. Prefer exact YYYY-MM-DD extracted from filename (already has year range guard).
        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1_000_000L) return filenameDate

        // 2. Year-only hint from filename, but only for plausible past years.
        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null && dt <= 0) {
            try {
                val year = yearMatch.value.toInt()
                if (year in 1980..currentYear) {
                    val cal = Calendar.getInstance()
                    cal.set(year, 0, 1, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (e: Exception) {}
        }

        // 3. Use metadata timestamps — but only if they are reasonable (no future/garbage dates).
        if (isReasonable(dt)) return dt
        if (isReasonable(da)) return da
        if (isReasonable(dm)) return dm

        // 4. All metadata is garbage or in the future — clamp to now so the file
        //    doesn't pollute far-future month headers like "January 2099".
        val best = when {
            dt > 1_000_000L -> dt
            da > 1_000_000L -> da
            dm > 1_000_000L -> dm
            else -> now
        }
        return best.coerceAtMost(now)
    }

    /**
     * Date resolver specifically for PDFs.
     *
     * PDFs have no EXIF. Their DATE_MODIFIED in MediaStore is the file-system modification
     * time, which is often copied verbatim from the PDF's internal creation/modification
     * metadata — meaning a PDF of a 1987 paper downloaded yesterday will show DATE_MODIFIED
     * of January 1987. DATE_ADDED (when MediaStore indexed the file) is therefore the most
     * trustworthy anchor for "when did this PDF arrive on the device."
     *
     * Strategy (in priority order):
     *   1. Exact date embedded in the filename (e.g. report_2024-03-15.pdf)
     *   2. Year hint in the filename, if plausible (1993–now; PDF format was born in 1993)
     *   3. DATE_ADDED — when MediaStore first saw the file (reliable for downloads)
     *   4. DATE_MODIFIED — accepted only if it's ≥ year 2000 (pre-2000 values almost always
     *      reflect embedded document metadata, not when the file arrived on the phone)
     *   5. Fallback: current time, so the file lands in "this month" rather than creating
     *      phantom headers like "January 1987"
     */
    private fun resolvePdfDate(name: String, dateAdded: Long, dateModified: Long): Long {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        fun yearOf(ts: Long): Int =
            Calendar.getInstance().also { it.timeInMillis = ts }.get(Calendar.YEAR)

        fun isUsable(ts: Long, minYear: Int): Boolean {
            if (ts <= 1_000_000L || ts > now) return false
            return yearOf(ts) in minYear..currentYear
        }

        // 1. Exact date from filename
        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1_000_000L) return filenameDate

        // 2. Year-only hint from filename (PDF era: 1993 onward)
        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null) {
            try {
                val year = yearMatch.value.toInt()
                if (year in 1993..currentYear) {
                    val cal = Calendar.getInstance()
                    cal.set(year, 0, 1, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {}
        }

        // 3. DATE_ADDED — best proxy for "arrived on device"; accept back to 1993
        if (isUsable(dateAdded, 1993)) return dateAdded

        // 4. DATE_MODIFIED — only trust it from year 2000 onward; older values almost
        //    always come from the PDF's own embedded metadata, not the download date
        if (isUsable(dateModified, 2000)) return dateModified

        // 5. Cannot determine a reliable date → use now
        return now
    }

    private fun extractDateFromFilename(name: String): Long {
        val match = filenameDateRegex.find(name)
        if (match != null) {
            try {
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt() - 1
                val day = match.groupValues[3].toInt()
                val maxYear = Calendar.getInstance().get(Calendar.YEAR)
                if (year in 1980..maxYear && month in 0..11 && day in 1..31) {
                    val cal = Calendar.getInstance()
                    cal.set(year, month, day, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (e: Exception) {}
        }
        return 0L
    }

    fun createDeleteRequest(items: List<MediaItem>): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { Uri.parse(it.uri) }
                return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            } catch (e: Exception) {
                Log.e("MediaRepository", "Delete request failed", e)
            }
        }
        return null
    }
}
