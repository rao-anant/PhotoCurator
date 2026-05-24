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

    private val _sortAscending = MutableLiveData<Boolean>()
    val sortAscending: LiveData<Boolean> = _sortAscending

    private val _includePdf = MutableLiveData<Boolean>()
    val includePdf: LiveData<Boolean> = _includePdf

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
    private var loadJob: Job? = null
    
    private var cachedRawMedia: List<MediaItem>? = null
    private var structuralVersion = 0

    init {
        _sortAscending.value = prefs.isSortAscending()
        _includePdf.value = prefs.isIncludePdf()
    }

    fun setIncludePdf(include: Boolean) {
        if (_includePdf.value != include) {
            _includePdf.value = include
            prefs.saveIncludePdf(include)
            // Just re-filter existing cache immediately
            loadMedia(forceRefresh = false)
        }
    }

    fun loadMedia(forceRefresh: Boolean = false) {
        val ascending = _sortAscending.value ?: true
        val pdfSetting = _includePdf.value ?: false
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

            // 2. IMMEDIATE FILTERING
            // We MUST remove items in 'deletedFingerprintsInFlight' before logic touches counts or grouping.
            // This ensures that even if MediaStore hasn't updated its index yet, the UI is correct and consistent.
            val filteredMedia = rawMedia.filter { !deletedFingerprintsInFlight.contains(getFingerprint(it)) }

            // 3. Update Stats based on the FILTERED list so top bar reflects deletions instantly
            _totalPhotos.postValue(filteredMedia.count { it.type == MediaType.IMAGE })
            _totalVideos.postValue(filteredMedia.count { it.type == MediaType.VIDEO })
            _totalPdfs.postValue(filteredMedia.count { it.type == MediaType.PDF })

            // 4. Filter display set based on PDF setting
            val displayMedia = if (pdfSetting) {
                filteredMedia
            } else {
                filteredMedia.filter { it.type != MediaType.PDF }
            }

            // 5. Process into groups
            val (visibleGroups, _) = repo.processAndGroupMedia(
                displayMedia, ascending, doneMonthKeys
            )
            
            // Recalculate done groups using filteredMedia to ensure counts match
            val (_, doneGroups) = repo.processAndGroupMedia(
                filteredMedia, ascending, doneMonthKeys
            )

            // 6. Build final flat list for Adapter
            val items = ArrayList<GalleryItem>(displayMedia.size + visibleGroups.size * 2) 
            for (group in visibleGroups) {
                // Header with accurate current count
                items.add(GalleryItem.Header(group.key, group.label, group.items.size, currentVersion))
                group.items.forEachIndexed { index, mediaItem -> 
                    items.add(GalleryItem.Media(mediaItem, group.key, index, currentVersion))
                }
                items.add(GalleryItem.Footer(group.key, currentVersion))
            }
            
            _galleryItems.postValue(items)
            _doneMonthsAvailable.postValue(doneGroups)
            _isLoading.postValue(false)
        }
    }

    fun toggleSort() {
        val next = !(_sortAscending.value ?: true)
        _sortAscending.value = next
        prefs.saveSortAscending(next)
        structuralVersion++
        loadMedia(forceRefresh = false) 
    }

    fun deleteMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        
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
                        _deletePermissionRequest.postValue(intentSender)
                    } else {
                        updateUiAfterDeletion(fingerprints)
                    }
                } else {
                    val resolver = getApplication<Application>().contentResolver
                    items.forEach { item ->
                        try { 
                            resolver.delete(android.net.Uri.parse(item.uri), null, null) 
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    updateUiAfterDeletion(fingerprints)
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
        pendingItemsToDelete = null
        if (success && items != null) {
            updateUiAfterDeletion(items.map { getFingerprint(it) })
        } else if (items != null) {
            // If cancelled, remove from flight so they reappear in grid
            deletedFingerprintsInFlight.removeAll(items.map { getFingerprint(it) })
            loadMedia(forceRefresh = true)
        }
        _deletePermissionRequest.value = null
    }

    private fun updateUiAfterDeletion(fingerprints: List<String>) {
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
        structuralVersion++
        loadMedia(forceRefresh = false)
    }
}
