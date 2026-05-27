package com.anant.mediacurator

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * A flexible ImageView that supports both fixed aspect ratios and natural height.
 */
class SquareImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    // Ratio of height to width. 
    // 1.0 = Square
    // 0.0 = Natural height (uses adjustViewBounds)
    var ratio: Float = 1.0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio > 0) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = (width * ratio).toInt()
            setMeasuredDimension(width, height)
        } else {
            // Use natural height (ensure adjustViewBounds="true" in XML)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
