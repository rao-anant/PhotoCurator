package com.anant.mediacurator

import androidx.recyclerview.widget.GridLayoutManager

class GallerySpanSizeLookup(
    private val adapter: GalleryAdapter,
    private val spanCount: Int
) : GridLayoutManager.SpanSizeLookup() {
    
    override fun getSpanSize(position: Int): Int {
        val item = adapter.currentList.getOrNull(position) ?: return 1
        
        // Year headers, month headers, and footers always take the full width
        if (item is GalleryItem.YearHeader || item is GalleryItem.Header || item is GalleryItem.Footer) {
            return spanCount
        }

        if (item is GalleryItem.Media) {
            val patternPos = item.indexInMonth % 10
            
            // Fixed logic to match the current spanCount.
            // If the user changes spanCount in MainActivity, this will adapt.
            val size = when (spanCount) {
                4 -> if (patternPos == 0) 2 else 1
                6 -> when (patternPos) {
                    0 -> 6
                    in 1..2 -> 3
                    in 3..5 -> 2
                    else -> 3
                }
                else -> 1 // Default for any other grid size
            }
            
            // Absolute safety: never return more than spanCount
            return Math.min(size, spanCount)
        }
        
        return 1
    }
}
