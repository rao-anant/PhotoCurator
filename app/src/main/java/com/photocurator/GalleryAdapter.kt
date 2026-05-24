package com.photocurator

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class GalleryAdapter(
    private val onMediaClick: (MediaItem) -> Unit,
    private val onMonthHide: (MonthGroup) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
        const val TYPE_FOOTER = 2
    }

    var currentList: List<GalleryItem> = emptyList()
        private set

    var selectionMode = false
        private set

    private val selectedIds = mutableSetOf<Long>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var diffJob: Job? = null

    fun submitList(newList: List<GalleryItem>) {
        val oldList = currentList
        val oldVersion = if (oldList.isNotEmpty()) oldList[0].structuralVersion else -1
        val newVersion = if (newList.isNotEmpty()) newList[0].structuralVersion else -1

        diffJob?.cancel()

        if (oldVersion != newVersion) {
            currentList = newList
            notifyDataSetChanged()
        } else {
            diffJob = adapterScope.launch {
                val diffResult = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                        override fun getOldListSize() = oldList.size
                        override fun getNewListSize() = newList.size
                        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                            val old = oldList[oldPos]
                            val new = newList[newPos]
                            return when {
                                old is GalleryItem.Header && new is GalleryItem.Header -> old.monthKey == new.monthKey
                                old is GalleryItem.Footer && new is GalleryItem.Footer -> old.monthKey == new.monthKey
                                old is GalleryItem.Media && new is GalleryItem.Media -> old.mediaItem.id == new.mediaItem.id
                                else -> false
                            }
                        }
                        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                            return oldList[oldPos] == newList[newPos]
                        }
                    }, false)
                }
                currentList = newList
                diffResult.dispatchUpdatesTo(this@GalleryAdapter)
            }
        }
    }

    fun toggleSelection(item: MediaItem) {
        val wasSelected = selectedIds.contains(item.id)
        if (wasSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)

        if (selectedIds.isEmpty()) {
            selectionMode = false
        } else {
            selectionMode = true
        }
        
        onSelectionChanged(selectedIds.size)
        
        val pos = currentList.indexOfFirst { it is GalleryItem.Media && it.mediaItem.id == item.id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun enterSelectionMode(item: MediaItem) {
        if (!selectionMode) {
            selectionMode = true
            selectedIds.add(item.id)
            onSelectionChanged(selectedIds.size)
            notifyDataSetChanged()
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun isSelected(item: MediaItem) = selectedIds.contains(item.id)

    fun getSelectedItems(): List<MediaItem> =
        currentList.filterIsInstance<GalleryItem.Media>()
            .filter { selectedIds.contains(it.mediaItem.id) }.map { it.mediaItem }

    override fun getItemCount() = currentList.size

    override fun getItemViewType(position: Int) = when (currentList[position]) {
        is GalleryItem.Header -> TYPE_HEADER
        is GalleryItem.Media -> TYPE_MEDIA
        is GalleryItem.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_month_header, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_month_footer, parent, false))
            else -> MediaViewHolder(inflater.inflate(R.layout.item_photo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is GalleryItem.Header -> (holder as HeaderViewHolder).bind(item)
            is GalleryItem.Footer -> (holder as FooterViewHolder).bind(item)
            is GalleryItem.Media -> (holder as MediaViewHolder).bind(item)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLabel: TextView = itemView.findViewById(R.id.tvMonthLabel)
        private val tvCount: TextView = itemView.findViewById(R.id.tvPhotoCount)

        fun bind(header: GalleryItem.Header) {
            tvLabel.text = header.label
            tvCount.text = "${header.count} item${if (header.count != 1) "s" else ""}"
        }
    }

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnHide: MaterialButton = itemView.findViewById(R.id.btnHideMonthFooter)
        
        fun bind(footer: GalleryItem.Footer) {
            btnHide.visibility = if (selectionMode) View.GONE else View.VISIBLE
            btnHide.setOnClickListener {
                val parts = footer.monthKey.split("-")
                if (parts.size == 2) {
                    val year = parts[0].toIntOrNull() ?: 0
                    val month = parts[1].toIntOrNull() ?: 0
                    val label = currentList.filterIsInstance<GalleryItem.Header>()
                        .find { it.monthKey == footer.monthKey }?.label ?: ""
                    
                    onMonthHide(MonthGroup(year, month, footer.monthKey, label))
                }
            }
        }
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.ivPhoto)
        private val overlay: View = itemView.findViewById(R.id.selectionOverlay)
        private val checkmark: ImageView = itemView.findViewById(R.id.ivCheckmark)
        private val typeIcon: ImageView = itemView.findViewById(R.id.ivTypeIcon)
        private val bottomGradient: View = itemView.findViewById(R.id.bottomGradient)

        fun bind(media: GalleryItem.Media) {
            val item = media.mediaItem
            val isFeatured = (media.indexInMonth % 10) == 0
            (imageView as? SquareImageView)?.ratio = if (isFeatured) 1.2f else 1.0f

            if (item.type == MediaType.PDF) {
                Glide.with(itemView.context).clear(imageView)
                // Replaced 'ic_menu_description' with 'ic_menu_edit' for better compatibility
                imageView.setImageResource(android.R.drawable.ic_menu_edit)
                imageView.setBackgroundColor(Color.parseColor("#EEEEEE"))
                imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                typeIcon.visibility = View.VISIBLE
                typeIcon.setImageResource(android.R.drawable.ic_menu_edit)
                bottomGradient.visibility = View.GONE
            } else {
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(itemView.context)
                    .asBitmap()
                    .load(item.uri)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .override(if (isFeatured) 800 else 400)
                    .thumbnail(0.1f)
                    .centerCrop()
                    .into(imageView)

                typeIcon.visibility = if (item.type != MediaType.IMAGE) View.VISIBLE else View.GONE
                if (item.type == MediaType.VIDEO) {
                    typeIcon.setImageResource(android.R.drawable.ic_media_play)
                    bottomGradient.visibility = View.VISIBLE
                } else {
                    bottomGradient.visibility = View.GONE
                }
            }

            val selected = isSelected(item)
            overlay.visibility = if (selected) View.VISIBLE else View.GONE
            checkmark.visibility = if (selected) View.VISIBLE else View.GONE
            imageView.alpha = if (selected) 0.6f else 1.0f
            
            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item)
                } else {
                    onMediaClick(item)
                }
            }
            
            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode(item)
                }
                true
            }
        }
    }
}
