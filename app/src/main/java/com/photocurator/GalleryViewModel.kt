package com.photocurator

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = PreferencesManager(app)

    private val _galleryItems = MutableLiveData<List<GalleryItem>>()
    val galleryItems: LiveData<List<GalleryItem>> = _galleryItems

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
    }

    private var pendingItemsToDelete: List<MediaItem>? = null
    private var pendingBytesToFree: Long = 0L
    private var loadJob: Job? = null

    private var cachedRawMedia: List<MediaItem>? = null
    private var structuralVersion = 0

    @Volatile private var pendingScrollToTop = false
    @Volatile private var pendingScrollToMonthKey: String? = null

    init {
        _sortMode.value = prefs.getSortMode()
        _includePhoto.value = prefs.isIncludePhoto()
        _includeVideo.value = prefs.isIncludeVideo()
        _includePdf.value = prefs.isIncludePdf()
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

            // 1. Get raw media from repository or local cache
            val rawMedia = if (forceRefresh || cachedRawMedia == null) {
                val fetched = repo.fetchAllMedia()
                cachedRawMedia = fetched
                fetched
            } else {
                cachedRawMedia!!
            }

            // 2. Remove items deleted but not yet reflected in MediaStore
            val filteredMedia = rawMedia.filter { !deletedFingerprintsInFlight.contains(getFingerprint(it)) }

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

            // 5. Process into groups
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

            // 7. Build flat list for Adapter
            val items = ArrayList<GalleryItem>(displayMedia.size + visibleGroups.size * 2)
            for (group in visibleGroups) {
                items.add(GalleryItem.Header(group.key, group.label, group.items.size, currentVersion))
                group.items.forEachIndexed { index, mediaItem ->
                    items.add(GalleryItem.Media(mediaItem, group.key, index, currentVersion))
                }
                items.add(GalleryItem.Footer(group.key, currentVersion))
            }

            _galleryItems.postValue(items)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intentSender = repo.createDeleteRequest(items)
                    if (intentSender != null) {
                        pendingItemsToDelete = items
                        pendingBytesToFree = totalBytes
                        _deletePermissionRequest.postValue(intentSender)
                    } else {
                        updateUiAfterDeletion(fingerprints, totalBytes)
                    }
                } else {
                    val resolver = getApplication<Application>().contentResolver
                    items.forEach { item ->
                        try {
                            resolver.delete(android.net.Uri.parse(item.uri), null, null)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    updateUiAfterDeletion(fingerprints, totalBytes)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // On error, restore items to UI
                deletedFingerprintsInFlight.removeAll(fingerprints)
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
            deletedFingerprintsInFlight.removeAll(items.map { getFingerprint(it) })
            loadMedia(forceRefresh = true)
        }
        _deletePermissionRequest.value = null
    }

    private fun updateUiAfterDeletion(fingerprints: List<String>, bytesFreed: Long = 0L) {
        if (bytesFreed > 0L) _storageSavedEvent.postValue(bytesFreed)
        // Signals activity to finish (if viewer) or refresh
        _deletionCompletedEvent.postValue(true)
        
        viewModelScope.launch {
            // Trigger multiple refreshes to keep UI in sync while MediaStore catches up in the background
            delay(500)
            loadMedia(forceRefresh = true)
            delay(2000)
            loadMedia(forceRefresh = true)
            
            // Clear tracking after a long delay once we are sure MediaStore is updated
            delay(10000)
            deletedFingerprintsInFlight.removeAll(fingerprints)
        }
    }

    fun markMonthDone(year: Int, month: Int) {
        prefs.markMonthDone(year, month)
        loadMedia(forceRefresh = false)
    }

    fun restoreMonth(year: Int, month: Int) {
        prefs.unmarkMonthDone(year, month)
        pendingScrollToMonthKey = prefs.monthKey(year, month)
        structuralVersion++
        loadMedia(forceRefresh = false)
    }
}
