package com.anant.mediacurator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
    private val onYearToggle: (Int) -> Unit,
    private val onMonthToggle: (String) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_YEAR_HEADER = 0
        const val TYPE_HEADER = 1
        const val TYPE_MEDIA  = 2
        const val TYPE_FOOTER = 3

        fun fmtBytes(b: Long): String = when {
            b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
            b >= 1_048_576L     -> "%.1f MB".format(b / 1_048_576.0)
            b >= 1_024L         -> "%.1f KB".format(b / 1_024.0)
            else                -> "$b B"
        }

        /** "3 photos · 1 video · 2 PDFs · 1.2 GB" — omits zero-count types */
        @JvmStatic
        fun formatTypeBreakdown(photos: Int, videos: Int, pdfs: Int, bytes: Long): String {
            val parts = mutableListOf<String>()
            if (photos > 0) parts.add("%,d %s".format(photos, if (photos == 1) "photo"  else "photos"))
            if (videos > 0) parts.add("%,d %s".format(videos, if (videos == 1) "video"  else "videos"))
            if (pdfs   > 0) parts.add("%,d PDF%s".format(pdfs, if (pdfs == 1) "" else "s"))
            if (parts.isEmpty()) parts.add("0 items")
            parts.add(fmtBytes(bytes))
            return parts.joinToString(" · ")
        }

        fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }

    var currentList: List<GalleryItem> = emptyList()
        private set

    var selectionMode = false
        private set

    private val selectedIds = mutableSetOf<Long>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var diffJob: Job? = null
    private val pdfThumbnailCache = mutableMapOf<Long, Bitmap?>()

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
                                old is GalleryItem.YearHeader && new is GalleryItem.YearHeader -> old.year == new.year
                                old is GalleryItem.Header     && new is GalleryItem.Header     -> old.monthKey == new.monthKey
                                old is GalleryItem.Footer     && new is GalleryItem.Footer     -> old.monthKey == new.monthKey
                                old is GalleryItem.Media      && new is GalleryItem.Media      -> old.mediaItem.id == new.mediaItem.id
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
        is GalleryItem.YearHeader -> TYPE_YEAR_HEADER
        is GalleryItem.Header     -> TYPE_HEADER
        is GalleryItem.Media      -> TYPE_MEDIA
        is GalleryItem.Footer     -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_YEAR_HEADER -> YearHeaderViewHolder(inflater.inflate(R.layout.item_year_header, parent, false))
            TYPE_HEADER      -> HeaderViewHolder(inflater.inflate(R.layout.item_month_header, parent, false))
            TYPE_FOOTER      -> FooterViewHolder(inflater.inflate(R.layout.item_month_footer, parent, false))
            else             -> MediaViewHolder(inflater.inflate(R.layout.item_photo, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is GalleryItem.YearHeader -> (holder as YearHeaderViewHolder).bind(item)
            is GalleryItem.Header     -> (holder as HeaderViewHolder).bind(item)
            is GalleryItem.Footer     -> (holder as FooterViewHolder).bind(item)
            is GalleryItem.Media      -> (holder as MediaViewHolder).bind(item)
        }
    }

    inner class YearHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvArrow: TextView = itemView.findViewById(R.id.tvExpandArrow)
        private val tvYear:  TextView = itemView.findViewById(R.id.tvYear)
        private val tvStats: TextView = itemView.findViewById(R.id.tvYearStats)

        fun bind(header: GalleryItem.YearHeader) {
            tvYear.text  = header.year.toString()
            tvStats.text = formatTypeBreakdown(header.photoCount, header.videoCount, header.pdfCount, header.totalBytes)
            tvArrow.text = if (header.isExpanded) "▼" else "▶"
            itemView.setOnClickListener { onYearToggle(header.year) }
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvArrow: TextView = itemView.findViewById(R.id.tvMonthArrow)
        private val tvLabel: TextView = itemView.findViewById(R.id.tvMonthLabel)
        private val tvCount: TextView = itemView.findViewById(R.id.tvPhotoCount)

        fun bind(header: GalleryItem.Header) {
            tvArrow.text = if (header.isExpanded) "▼" else "▶"
            tvLabel.text = header.label
            tvCount.text = formatTypeBreakdown(header.photoCount, header.videoCount, header.pdfCount, header.totalBytes)
            itemView.setOnClickListener { onMonthToggle(header.monthKey) }
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
        private val infoLabel: TextView = itemView.findViewById(R.id.tvDuration)
        private val dateLabel: TextView = itemView.findViewById(R.id.tvDate)
        private var pdfJob: Job? = null

        fun bind(media: GalleryItem.Media) {
            pdfJob?.cancel()
            pdfJob = null

            val item = media.mediaItem
            val isFeatured = (media.indexInMonth % 10) == 0
            (imageView as? SquareImageView)?.ratio = if (isFeatured) 1.2f else 1.0f

            if (item.type == MediaType.PDF) {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageResource(R.drawable.ic_pdf)
                imageView.setBackgroundColor(Color.parseColor("#F5F5F5"))
                imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                typeIcon.visibility = View.GONE
                imageView.tag = item.id

                val cached = pdfThumbnailCache[item.id]
                when {
                    cached != null -> {
                        imageView.setImageBitmap(cached)
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    !pdfThumbnailCache.containsKey(item.id) -> {
                        val appContext = itemView.context.applicationContext
                        pdfJob = adapterScope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                try {
                                    val uri = Uri.parse(item.uri)
                                    appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                        PdfRenderer(pfd).use { renderer ->
                                            renderer.openPage(0).use { page ->
                                                val targetSize = 400
                                                val scale = targetSize.toFloat() / maxOf(page.width, page.height)
                                                val w = (page.width * scale).toInt().coerceAtLeast(1)
                                                val h = (page.height * scale).toInt().coerceAtLeast(1)
                                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                                bmp.eraseColor(android.graphics.Color.WHITE)
                                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                bmp
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            pdfThumbnailCache[item.id] = bitmap
                            if (imageView.tag == item.id && bitmap != null) {
                                imageView.setImageBitmap(bitmap)
                                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        }
                    }
                }
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
                }
            }

            // Size (and duration for videos) shown on every item.
            val sizeStr = fmtBytes(item.size)
            infoLabel.text = if (item.type == MediaType.VIDEO && item.duration > 0) {
                "${formatDuration(item.duration)} · $sizeStr"
            } else {
                sizeStr
            }
            infoLabel.visibility = View.VISIBLE
            bottomGradient.visibility = View.VISIBLE

            // Date badge — only in flat (SIZE_ABSOLUTE) mode
            if (media.dateLabel != null) {
                dateLabel.text = media.dateLabel
                dateLabel.visibility = View.VISIBLE
            } else {
                dateLabel.visibility = View.GONE
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
