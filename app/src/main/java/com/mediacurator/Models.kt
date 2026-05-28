package com.anant.mediacurator

import java.io.Serializable

enum class MediaType {
    IMAGE, VIDEO, PDF
}

enum class SortMode {
    DATE_NEWEST, DATE_OLDEST, SIZE_ABSOLUTE, SIZE_WITHIN_MONTH, COUNT_PER_MONTH
}

data class MediaStats(
    // Counts: visible = in non-hidden months (all types, ignores chip filter)
    val visiblePhotos: Int, val hiddenPhotos: Int, val totalPhotos: Int,
    val visibleVideos: Int, val hiddenVideos: Int, val totalVideos: Int,
    val visiblePdfs:   Int, val hiddenPdfs:   Int, val totalPdfs:   Int,
    // Sizes in bytes
    val visiblePhotoBytes: Long, val hiddenPhotoBytes: Long,
    val visibleVideoBytes: Long, val hiddenVideoBytes: Long,
    val visiblePdfBytes:   Long, val hiddenPdfBytes:   Long,
    // Integrity
    val integrityOk: Boolean,
    val integrityDetail: String
)

data class MediaItem(
    val id: Long,
    val uri: String,
    val volume: String,
    val dateTaken: Long,
    val displayName: String,
    val size: Long,
    val type: MediaType,
    val duration: Long = 0
) : Serializable

data class MonthGroup(
    val year: Int,
    val month: Int,
    val key: String,
    val label: String,
    val items: MutableList<MediaItem> = mutableListOf()
) : Serializable

sealed class GalleryItem {
    // structuralVersion signals the Adapter that a full UI reset is needed (notifyDataSetChanged)
    // to bypass expensive O(N^2) DiffUtil move calculations during large list changes like Sorting.
    abstract val structuralVersion: Int

    data class YearHeader(
        val year: Int,
        val totalItems: Int,
        val totalBytes: Long,
        val isExpanded: Boolean,
        val photoCount: Int = 0,
        val videoCount: Int = 0,
        val pdfCount: Int = 0,
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class Header(
        val monthKey: String,
        val label: String,
        val count: Int,
        val totalBytes: Long = 0L,
        val isExpanded: Boolean = false,
        val photoCount: Int = 0,
        val videoCount: Int = 0,
        val pdfCount: Int = 0,
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class Media(
        val mediaItem: MediaItem,
        val monthKey: String,
        val indexInMonth: Int,
        // Non-null only in flat (SIZE_ABSOLUTE) mode — shown as a badge on the thumbnail
        // so the user knows which month each item belongs to without tree headers.
        val dateLabel: String? = null,
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class Footer(
        val monthKey: String,
        override val structuralVersion: Int = 0
    ) : GalleryItem()
}
