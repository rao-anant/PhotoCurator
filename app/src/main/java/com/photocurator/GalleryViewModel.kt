package com.photocurator

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = PreferencesManager(app)

    private val _galleryItems = MutableLiveData<List<GalleryItem>>()
    val galleryItems: LiveData<List<GalleryItem>> = _galleryItems

    // Flat list of every visible MediaItem (type-filtered, deletion-filtered) with no
    // tree-view expansion gate.  MediaViewerActivity uses this so it can swipe through
    // all items regardless of which years/months are currently expanded in the grid.
    private val _flatMediaItems = MutableLiveData<List<MediaItem>>()
    val flatMediaItems: LiveData<List<MediaItem>> = _flatMediaItems

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _sortMode = MutableLiveData<SortMode>()
    val sortMode: LiveData<SortMode> = _sortMode

    private val _storageSavedEvent = MutableLiveData<Long>()
    val storageSavedEvent: LiveData<Long> = _storageSavedEvent

    private val _includePhoto = MutableLiveData<Boolean>()
    val includePhoto: LiveData<Boolean> = _includePhoto

    private val _includeVideo = MutableLiveData<Boolean>()
    val includeVideo: LiveData<Boolean> = _includeVideo

    private val _includePdf = MutableLiveData<Boolean>()
    val includePdf: LiveData<Boolean> = _includePdf

    private val _mediaStats = MutableLiveData<MediaStats?>()
    val mediaStats: LiveData<MediaStats?> = _mediaStats

    private val _scrollToTopEvent = MutableLiveData<Unit>()
    val scrollToTopEvent: LiveData<Unit> = _scrollToTopEvent

    private val _scrollToMonthKey = MutableLiveData<String?>()
    val scrollToMonthKey: LiveData<String?> = _scrollToMonthKey

    private val _deletePermissionRequest = MutableLiveData<IntentSender?>()
    val deletePermissionRequest: LiveData<IntentSender?> = _deletePermissionRequest

    private val _deletionCompletedEvent = MutableLiveData<Boolean>()
    val deletionCompletedEvent: LiveData<Boolean> = _deletionCompletedEvent

    private val _doneMonthsAvailable = MutableLiveData<List<MonthGroup>>()
    val doneMonthsAvailable: LiveData<List<MonthGroup>> = _doneMonthsAvailable

    private val _totalPhotos = MutableLiveData<Int>(0)
    val totalPhotos: LiveData<Int> = _totalPhotos

    private val _totalVideos = MutableLiveData<Int>(0)
    val totalVideos: LiveData<Int> = _totalVideos

    private val _totalPdfs = MutableLiveData<Int>(0)
    val totalPdfs: LiveData<Int> = _totalPdfs

    companion object {
        // Shared across Activity instances (MainActivity and ViewerActivity)
        // to ensure consistent UI filtering during the lag between file deletion
        // and the system MediaStore index updating.
        // Fingerprints (name + size) are more stable than IDs across different query collection views.
        private val deletedFingerprintsInFlight = Collections.synchronizedSet(mutableSetOf<String>())

        fun getFingerprint(item: MediaItem): String = "${item.displayName}_${item.size}"

        // Fixed filename so we can always overwrite / locate the single live backup.
        const val AUTO_BACKUP_FILENAME = "mediacurator_hidden.json"
    }

    private var pendingItemsToDelete: List<MediaItem>? = null
    private var pendingBytesToFree: Long = 0L
    private var loadJob: Job? = null
    private var mediaObserver: ContentObserver? = null

    private var cachedRawMedia: List<MediaItem>? = null
    private var structuralVersion = 0

    // Items deleted during this session — NEVER cleared while the app is running.
    // This is the definitive guard: once a user deletes something it must not reappear
    // regardless of how slowly (or incompletely) MediaStore updates its index.
    // Unlike deletedFingerprintsInFlight (static, shared, time-limited), this set is
    // per-instance and lives for the entire ViewModel lifetime.
    private val sessionDeletedFingerprints = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile private var pendingScrollToTop = false
    @Volatile private var pendingScrollToMonthKey: String? = null

    // Which years / months are currently expanded in the tree view.
    // Thread-safe: reads happen on IO thread (via snapshot), writes on main thread.
    private val expandedYears  = Collections.synchronizedSet(mutableSetOf<Int>())
    private val expandedMonths = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        _sortMode.value = prefs.getSortMode()
        _includePhoto.value = prefs.isIncludePhoto()
        _includeVideo.value = prefs.isIncludeVideo()
        _includePdf.value = prefs.isIncludePdf()
        // Silently restore hidden-month state from the auto-backup if prefs are empty
        // (covers fresh install, app-data clear, reinstall after uninstall).
        checkAndAutoRestore()
        registerMediaObserver()
    }

    fun setIncludePhoto(include: Boolean) {
        if (_includePhoto.value != include) {
            _includePhoto.value = include
            prefs.saveIncludePhoto(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun setIncludeVideo(include: Boolean) {
        if (_includeVideo.value != include) {
            _includeVideo.value = include
            prefs.saveIncludeVideo(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun setIncludePdf(include: Boolean) {
        if (_includePdf.value != include) {
            _includePdf.value = include
            prefs.saveIncludePdf(include)
            pendingScrollToTop = true
            loadMedia(forceRefresh = false)
        }
    }

    fun clearScrollToMonth() {
        _scrollToMonthKey.value = null
    }

    fun toggleYearExpansion(year: Int) {
        if (expandedYears.contains(year)) expandedYears.remove(year) else expandedYears.add(year)
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    fun toggleMonthExpansion(monthKey: String) {
        if (expandedMonths.contains(monthKey)) expandedMonths.remove(monthKey) else expandedMonths.add(monthKey)
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value != mode) {
            _sortMode.value = mode
            prefs.saveSortMode(mode)
            structuralVersion++
            loadMedia(forceRefresh = false)
        }
    }

    fun loadMedia(forceRefresh: Boolean = false) {
        val sortMode = _sortMode.value ?: SortMode.DATE_OLDEST
        val photoOn = _includePhoto.value ?: true
        val videoOn = _includeVideo.value ?: true
        val pdfOn = _includePdf.value ?: true
        val doneMonthKeys = prefs.getDoneMonths()
        val currentVersion = structuralVersion

        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)

            // 1. Get raw media from repository or local cache.
            val rawMedia = if (forceRefresh || cachedRawMedia == null) {
                val fetched = repo.fetchAllMedia()

                // Data-driven cleanup for the cross-Activity shared set: clear a fingerprint
                // only when MediaStore confirms the item is actually gone.
                if (deletedFingerprintsInFlight.isNotEmpty()) {
                    val presentFps    = fetched.mapTo(HashSet()) { getFingerprint(it) }
                    val snapshot      = deletedFingerprintsInFlight.toSet()
                    val confirmedGone = snapshot.filter { !presentFps.contains(it) }
                    if (confirmedGone.isNotEmpty()) deletedFingerprintsInFlight.removeAll(confirmedGone)
                }

                // Cache without ANY deleted items — both the shared set and the
                // session set (which is NEVER cleared, so items can't sneak back
                // no matter how slowly MediaStore updates).
                cachedRawMedia = fetched.filter { item ->
                    val fp = getFingerprint(item)
                    !deletedFingerprintsInFlight.contains(fp) && !sessionDeletedFingerprints.contains(fp)
                }
                fetched
            } else {
                cachedRawMedia!!
            }

            // 2. Filter display list — check both sets
            val filteredMedia = rawMedia.filter { item ->
                val fp = getFingerprint(item)
                !deletedFingerprintsInFlight.contains(fp) && !sessionDeletedFingerprints.contains(fp)
            }

            // 3. Update stats from full filtered list (not display-filtered)
            _totalPhotos.postValue(filteredMedia.count { it.type == MediaType.IMAGE })
            _totalVideos.postValue(filteredMedia.count { it.type == MediaType.VIDEO })
            _totalPdfs.postValue(filteredMedia.count { it.type == MediaType.PDF })

            // 4. Apply type filters
            val displayMedia = filteredMedia.filter { item ->
                when (item.type) {
                    MediaType.IMAGE -> photoOn
                    MediaType.VIDEO -> videoOn
                    MediaType.PDF -> pdfOn
                }
            }

            // 5. Process into groups (always needed for stats and done-months panel)
            val (visibleGroups, _) = repo.processAndGroupMedia(displayMedia, sortMode, doneMonthKeys)
            val (allVisibleFull, doneGroups) = repo.processAndGroupMedia(filteredMedia, sortMode, doneMonthKeys)

            // 6. Build MediaStats (counts + sizes per type, integrity check)
            fun countOf(groups: List<MonthGroup>, t: MediaType) = groups.sumOf { g -> g.items.count { it.type == t } }
            fun bytesOf(groups: List<MonthGroup>, t: MediaType) = groups.sumOf { g -> g.items.filter { it.type == t }.sumOf { it.size } }

            val vPhotos = countOf(allVisibleFull, MediaType.IMAGE); val hPhotos = countOf(doneGroups, MediaType.IMAGE)
            val vVideos = countOf(allVisibleFull, MediaType.VIDEO); val hVideos = countOf(doneGroups, MediaType.VIDEO)
            val vPdfs   = countOf(allVisibleFull, MediaType.PDF);   val hPdfs   = countOf(doneGroups, MediaType.PDF)

            val checkVisible = allVisibleFull.sumOf { it.items.size }
            val checkHidden  = doneGroups.sumOf { it.items.size }
            val checkTotal   = filteredMedia.size
            val integrityOk  = checkVisible + checkHidden == checkTotal
            val integrityDetail = if (integrityOk) "✓ All counts match"
                else "⚠ visible($checkVisible) + hidden($checkHidden) = ${checkVisible + checkHidden} ≠ total($checkTotal)"

            _mediaStats.postValue(MediaStats(
                vPhotos, hPhotos, filteredMedia.count { it.type == MediaType.IMAGE },
                vVideos, hVideos, filteredMedia.count { it.type == MediaType.VIDEO },
                vPdfs,   hPdfs,   filteredMedia.count { it.type == MediaType.PDF   },
                bytesOf(allVisibleFull, MediaType.IMAGE), bytesOf(doneGroups, MediaType.IMAGE),
                bytesOf(allVisibleFull, MediaType.VIDEO), bytesOf(doneGroups, MediaType.VIDEO),
                bytesOf(allVisibleFull, MediaType.PDF),   bytesOf(doneGroups, MediaType.PDF),
                integrityOk, integrityDetail
            ))

            // 7. Build gallery item list — flat for SIZE_ABSOLUTE, tree for everything else
            val galleryItems: List<GalleryItem>
            val flatForViewer: List<MediaItem>

            if (sortMode == SortMode.SIZE_ABSOLUTE) {
                // Pure size-descending flat list: no year/month headers at all.
                // A date badge (e.g. "Jan 2024") is embedded in each Media item so
                // the user knows the month without needing tree structure.
                val dateFmt  = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                val calendar = java.util.Calendar.getInstance()
                val flatSorted = displayMedia.sortedWith(
                    compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken }
                )
                galleryItems = flatSorted.mapIndexed { idx, mediaItem ->
                    calendar.timeInMillis = mediaItem.dateTaken
                    GalleryItem.Media(mediaItem, "", idx, dateFmt.format(calendar.time), currentVersion)
                }
                flatForViewer = flatSorted
            } else {
                // Normal tree view grouped by year → month.
                //
                // Header counts/sizes always reflect ALL item types (allVisibleFull),
                // not just the ones matching the current chip filter.  This keeps the
                // header stable — "203 items · 45.2 MB" — regardless of which type
                // chips are active, so the user can see the true contents of each month
                // even when only Videos (or Photos, or PDFs) are displayed.
                // The thumbnails shown when a month is expanded are still type-filtered.
                val allMonthLookup = allVisibleFull.associateBy { it.key }
                val allYearStats   = allVisibleFull.groupBy { it.year }.mapValues { (_, groups) ->
                    Pair(
                        groups.sumOf { it.items.size },
                        groups.sumOf { g -> g.items.sumOf { it.size } }
                    )
                }

                val yearToMonths = LinkedHashMap<Int, MutableList<MonthGroup>>()
                for (group in visibleGroups) {
                    yearToMonths.getOrPut(group.year) { mutableListOf() }.add(group)
                }

                val expandedYearsSnapshot  = expandedYears.toSet()
                val expandedMonthsSnapshot = expandedMonths.toSet()

                val treeItems = ArrayList<GalleryItem>()
                for ((year, months) in yearToMonths) {
                    val (yearItemCount, yearBytes) = allYearStats[year] ?: Pair(
                        months.sumOf { it.items.size },
                        months.sumOf { g -> g.items.sumOf { it.size } }
                    )
                    val isYearExpanded = expandedYearsSnapshot.contains(year)
                    treeItems.add(GalleryItem.YearHeader(year, yearItemCount, yearBytes, isYearExpanded, currentVersion))
                    if (isYearExpanded) {
                        for (group in months) {
                            val allGroup        = allMonthLookup[group.key]
                            val monthItemCount  = allGroup?.items?.size ?: group.items.size
                            val monthBytes      = allGroup?.items?.sumOf { it.size } ?: group.items.sumOf { it.size }
                            val isMonthExpanded = expandedMonthsSnapshot.contains(group.key)
                            treeItems.add(GalleryItem.Header(group.key, group.label, monthItemCount, monthBytes, isMonthExpanded, currentVersion))
                            if (isMonthExpanded) {
                                group.items.forEachIndexed { index, mediaItem ->
                                    treeItems.add(GalleryItem.Media(mediaItem, group.key, index, null, currentVersion))
                                }
                                treeItems.add(GalleryItem.Footer(group.key, currentVersion))
                            }
                        }
                    }
                }
                galleryItems = treeItems
                flatForViewer = visibleGroups.flatMap { it.items }
            }

            _flatMediaItems.postValue(flatForViewer)
            _galleryItems.postValue(galleryItems)
            _doneMonthsAvailable.postValue(doneGroups)
            _isLoading.postValue(false)

            // Fire scroll events after list is posted (both deliver on main thread in order)
            if (pendingScrollToTop) {
                pendingScrollToTop = false
                _scrollToTopEvent.postValue(Unit)
            }
            val monthKey = pendingScrollToMonthKey
            if (monthKey != null) {
                pendingScrollToMonthKey = null
                _scrollToMonthKey.postValue(monthKey)
            }
        }
    }

    fun deleteMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return

        val totalBytes = items.sumOf { it.size }
        val fingerprints = items.map { getFingerprint(it) }
        deletedFingerprintsInFlight.addAll(fingerprints)
        sessionDeletedFingerprints.addAll(fingerprints)   // permanent for this session

        // Instant UI feedback for the current instance: Update observers immediately
        val currentList = _galleryItems.value?.toMutableList() ?: mutableListOf()
        currentList.removeAll { it is GalleryItem.Media && fingerprints.contains(getFingerprint(it.mediaItem)) }
        _galleryItems.value = currentList
        
        // Also update local cache so a quick reload doesn't bring them back while still in flight
        cachedRawMedia = cachedRawMedia?.filter { !fingerprints.contains(getFingerprint(it)) }
        
        // Update stats immediately for the top bar
        cachedRawMedia?.let { media ->
            _totalPhotos.postValue(media.count { it.type == MediaType.IMAGE })
            _totalVideos.postValue(media.count { it.type == MediaType.VIDEO })
            _totalPdfs.postValue(media.count { it.type == MediaType.PDF })
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()
                ) {
                    // MANAGE_EXTERNAL_STORAGE is granted (required for PDF access).
                    // ContentResolver.delete works directly for any file on external
                    // storage — no system dialog needed.
                    items.forEach { item ->
                        try {
                            resolver.delete(android.net.Uri.parse(item.uri), null, null)
                        } catch (e: Exception) {
                            Log.e("GalleryViewModel", "Direct delete failed: ${item.displayName}: ${e.message}")
                        }
                    }
                    updateUiAfterDeletion(fingerprints, totalBytes)

                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // No MANAGE_EXTERNAL_STORAGE — request user consent via system dialog.
                    val intentSender = repo.createDeleteRequest(items)
                    if (intentSender != null) {
                        pendingItemsToDelete = items
                        pendingBytesToFree = totalBytes
                        _deletePermissionRequest.postValue(intentSender)
                    } else {
                        // createDeleteRequest failed (exception logged in MediaRepository).
                        // Roll back — do NOT pretend success.
                        deletedFingerprintsInFlight.removeAll(fingerprints)
                        sessionDeletedFingerprints.removeAll(fingerprints)
                        cachedRawMedia = null
                        loadMedia(forceRefresh = true)
                    }

                } else {
                    // Pre-R: attempt direct deletion.
                    items.forEach { item ->
                        try {
                            resolver.delete(android.net.Uri.parse(item.uri), null, null)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    updateUiAfterDeletion(fingerprints, totalBytes)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                deletedFingerprintsInFlight.removeAll(fingerprints)
                sessionDeletedFingerprints.removeAll(fingerprints)
                cachedRawMedia = null
                loadMedia(forceRefresh = true)
            }
        }
    }

    fun onDeletePermissionResult(success: Boolean) {
        val items = pendingItemsToDelete
        val bytes = pendingBytesToFree
        pendingItemsToDelete = null
        pendingBytesToFree = 0L
        if (success && items != null) {
            updateUiAfterDeletion(items.map { getFingerprint(it) }, bytes)
        } else if (items != null) {
            val fps = items.map { getFingerprint(it) }
            deletedFingerprintsInFlight.removeAll(fps)
            sessionDeletedFingerprints.removeAll(fps)   // roll back — user cancelled
            loadMedia(forceRefresh = true)
        }
        _deletePermissionRequest.value = null
    }

    private fun updateUiAfterDeletion(fingerprints: List<String>, bytesFreed: Long = 0L) {
        if (bytesFreed > 0L) _storageSavedEvent.postValue(bytesFreed)

        // Immediately remove deleted items from the flat list so MediaViewerActivity
        // can advance to the next item without waiting for the first MediaStore refresh.
        val flatNow = _flatMediaItems.value
        if (!flatNow.isNullOrEmpty()) {
            _flatMediaItems.postValue(flatNow.filter { !fingerprints.contains(getFingerprint(it)) })
        }

        _deletionCompletedEvent.postValue(true)

        viewModelScope.launch {
            // Progressive refreshes to sync with MediaStore.
            // sessionDeletedFingerprints guarantees items never reappear in this session
            // regardless of how slowly (or incompletely) MediaStore updates its index.
            // deletedFingerprintsInFlight auto-clears once MediaStore confirms deletion.
            // NO timer-based cleanup — timers caused race conditions with overlapping deletions.
            delay(500);   loadMedia(forceRefresh = true)
            delay(2000);  loadMedia(forceRefresh = true)
            delay(5000);  loadMedia(forceRefresh = true)
            delay(15000); loadMedia(forceRefresh = true)
        }
    }

    fun markMonthDone(year: Int, month: Int) {
        prefs.markMonthDone(year, month)
        autoSaveBackup()
        loadMedia(forceRefresh = false)
    }

    fun restoreMonth(year: Int, month: Int) {
        prefs.unmarkMonthDone(year, month)
        autoSaveBackup()
        val key = prefs.monthKey(year, month)
        expandedYears.add(year)   // ensure year is open so we can scroll to the month
        expandedMonths.add(key)   // ensure month is open so items are visible
        pendingScrollToMonthKey = key
        structuralVersion++
        loadMedia(forceRefresh = false)
    }

    // ── MediaStore observer ───────────────────────────────────────────────────

    /**
     * Watches MediaStore for new images, videos, and downloads.  When any change
     * arrives (e.g. WhatsApp saves a received video) we schedule a debounced
     * forceRefresh so the item appears automatically without the user having to
     * tap the refresh button.
     *
     * 3-second debounce: a burst of incoming files (e.g. bulk WhatsApp gallery
     * download) coalesces into a single reload rather than one per file.
     *
     * Skipped while the app's own deletion is in flight — deletedFingerprintsInFlight
     * being non-empty means we caused the MediaStore change ourselves, and
     * updateUiAfterDeletion already schedules its own progressive refreshes.
     */
    private fun registerMediaObserver() {
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable { loadMedia(forceRefresh = true) }

        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Skip if we triggered this change via our own deletion.
                if (deletedFingerprintsInFlight.isNotEmpty()) return
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 3_000L)
            }
        }

        val resolver = getApplication<Application>().contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver!!
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.registerContentObserver(
                MediaStore.Downloads.getContentUri("external"), true, mediaObserver!!
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
    }

    // ── Auto-backup ───────────────────────────────────────────────────────────

    /**
     * Silently write the current hidden-month set to mediacurator_hidden.json in Downloads.
     * Called after every hide / unhide.  If the set is empty the file is deleted.
     * Runs entirely on IO — no UI impact.
     */
    private fun autoSaveBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val months = prefs.getDoneMonths()
                val app    = getApplication<Application>()
                val resolver = app.contentResolver

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Downloads.getContentUri("external")

                    // Remove any existing copy we own (silently ignore if owned by old UID after reinstall)
                    try {
                        resolver.delete(
                            collection,
                            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                            arrayOf(AUTO_BACKUP_FILENAME)
                        )
                    } catch (_: Exception) {}

                    if (months.isEmpty()) return@launch  // nothing to write; deletion is the "backup"

                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, AUTO_BACKUP_FILENAME)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(collection, values) ?: return@launch
                    resolver.openOutputStream(uri)?.use { it.write(buildBackupJson(months).toByteArray(Charsets.UTF_8)) }
                } else {
                    @Suppress("DEPRECATION")
                    val file = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        AUTO_BACKUP_FILENAME
                    )
                    if (months.isEmpty()) { file.delete(); return@launch }
                    file.parentFile?.mkdirs()
                    file.writeText(buildBackupJson(months), Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Auto-backup failed", e)
            }
        }
    }

    /**
     * On first launch (or after app-data clear / reinstall), silently restore from the
     * auto-backup file in Downloads if local prefs are empty.
     */
    private fun checkAndAutoRestore() {
        if (prefs.getDoneMonths().isNotEmpty()) return  // prefs already populated — nothing to do
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app      = getApplication<Application>()
                val resolver = app.contentResolver
                val json: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Downloads.getContentUri("external")
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(AUTO_BACKUP_FILENAME),
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val id  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = ContentUris.withAppendedId(collection, id)
                        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val file = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        AUTO_BACKUP_FILENAME
                    )
                    if (file.exists()) file.readText(Charsets.UTF_8) else null
                }

                if (json != null) {
                    val obj    = org.json.JSONObject(json)
                    val arr    = obj.getJSONArray("hiddenMonths")
                    val months = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    if (months.isNotEmpty()) {
                        prefs.setDoneMonths(months)
                        Log.i("GalleryViewModel", "Auto-restored ${months.size} hidden months from backup")
                    }
                }
            } catch (e: Exception) {
                Log.e("GalleryViewModel", "Auto-restore failed", e)
            }
        }
    }

    private fun buildBackupJson(months: Set<String>): String {
        val ts  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val arr = months.sorted().joinToString(",\n    ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"exportedAt\": \"$ts\",\n  \"hiddenMonths\": [\n    $arr\n  ]\n}"
    }
}
