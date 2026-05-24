package com.photocurator

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
        val collections = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Downloads.getContentUri(volume))
        }
        collections.add(MediaStore.Files.getContentUri(volume))

        val selection = "${MediaStore.MediaColumns.SIZE} > 0 AND (" +
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE '%pdf%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.pdf' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.PDF')"

        for (collectionUri in collections) {
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
                        
                        val bestDate = resolveBestDate(name, 0, dm, da)
                        val uri = ContentUris.withAppendedId(collectionUri, id).toString()
                        
                        mediaList.add(MediaItem(id, uri, "external", bestDate, name, size, MediaType.PDF))
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "Error fetching PDFs from $collectionUri", e)
            }
        }
    }

    fun processAndGroupMedia(
        rawMedia: List<MediaItem>,
        ascending: Boolean,
        doneMonthKeys: Set<String>
    ): Pair<List<MonthGroup>, List<MonthGroup>> {
        val sortedList = if (ascending) {
            rawMedia.sortedWith(compareBy({ it.dateTaken }, { it.id }))
        } else {
            rawMedia.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
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
        return Pair(visibleGroups, doneGroups)
    }

    private fun resolveBestDate(name: String, dt: Long, dm: Long, da: Long): Long {
        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1000000) return filenameDate
        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null && dt <= 0) {
            try {
                val year = yearMatch.value.toInt()
                val cal = Calendar.getInstance()
                cal.set(year, 0, 1, 12, 0)
                return cal.timeInMillis
            } catch (e: Exception) {}
        }
        if (dt > 1000000) return dt
        if (da > 1000000) return da
        if (dm > 1000000) return dm
        return System.currentTimeMillis()
    }

    private fun extractDateFromFilename(name: String): Long {
        val match = filenameDateRegex.find(name)
        if (match != null) {
            try {
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt() - 1
                val day = match.groupValues[3].toInt()
                if (year in 1990..2030 && month in 0..11 && day in 1..31) {
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
