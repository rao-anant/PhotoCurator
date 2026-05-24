package com.photocurator

import java.io.Serializable

enum class MediaType {
    IMAGE, VIDEO, PDF
}

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

    data class Header(
        val monthKey: String, 
        val label: String, 
        val count: Int, 
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class Media(
        val mediaItem: MediaItem, 
        val monthKey: String,
        val indexInMonth: Int,
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class Footer(
        val monthKey: String, 
        override val structuralVersion: Int = 0
    ) : GalleryItem()
}
