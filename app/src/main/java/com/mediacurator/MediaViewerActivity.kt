package com.anant.mediacurator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.anant.mediacurator.databinding.ActivityMediaViewerBinding
import com.anant.mediacurator.databinding.ItemViewerMediaBinding

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: MediaPagerAdapter
    
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressAction: Runnable? = null

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            viewModel.onDeletePermissionResult(success)
            // Don't finish here — flatMediaItems update will advance to the next item,
            // and the viewer closes automatically when the list becomes empty.
        }

    companion object {
        const val EXTRA_START_POSITION = "extra_start_position"
        const val EXTRA_START_ID = "extra_start_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val startId = intent.getLongExtra(EXTRA_START_ID, -1L)
        val startPosition = intent.getIntExtra(EXTRA_START_POSITION, 0)

        viewModel.flatMediaItems.observe(this) { mediaItems ->
            if (mediaItems.isEmpty() && viewModel.isLoading.value == false) {
                finish()
                return@observe
            }

            if (mediaItems.isNotEmpty()) {
                if (!::adapter.isInitialized) {
                    adapter = MediaPagerAdapter(mediaItems) { finish() }
                    binding.viewPager.adapter = adapter

                    // Find position by ID if provided, else fallback to index
                    val initialPos = if (startId != -1L) {
                        mediaItems.indexOfFirst { it.id == startId }.takeIf { it != -1 } ?: startPosition
                    } else {
                        startPosition
                    }

                    binding.viewPager.setCurrentItem(initialPos, false)

                    binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            if (position < mediaItems.size) {
                                updateUIForItem(mediaItems[position])
                            }
                        }
                    })
                    if (initialPos < mediaItems.size) {
                        updateUIForItem(mediaItems[initialPos])
                    }
                } else {
                    val prevPos = binding.viewPager.currentItem
                    adapter.updateItems(mediaItems)
                    // Deleted item was the last one — back up to the new last item
                    if (prevPos >= mediaItems.size) {
                        val newPos = mediaItems.size - 1
                        binding.viewPager.setCurrentItem(newPos, false)
                        updateUIForItem(mediaItems[newPos])
                    }
                }
            }
        }

        viewModel.deletePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }

        // deletionCompletedEvent no longer closes the viewer — the flatMediaItems
        // observer advances to the next item and closes only when the list is empty.

        binding.btnShare.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val currentItem = adapter.getItem(binding.viewPager.currentItem)
                shareMedia(currentItem)
            }
        }

        binding.btnDelete.setOnClickListener {
            if (::adapter.isInitialized && binding.viewPager.currentItem < adapter.itemCount) {
                val currentItem = adapter.getItem(binding.viewPager.currentItem)
                viewModel.deleteMedia(listOf(currentItem))
            }
        }

        binding.videoScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    getCurrentViewHolder()?.binding?.videoView?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        viewModel.loadMedia()
    }

    private fun getCurrentViewHolder(): MediaPagerAdapter.ViewHolder? {
        if (!::binding.isInitialized) return null
        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView
        return recyclerView?.findViewHolderForAdapterPosition(binding.viewPager.currentItem) as? MediaPagerAdapter.ViewHolder
    }

    private fun updateUIForItem(item: MediaItem) {
        val isVideo = item.type == MediaType.VIDEO
        binding.videoScrubber.isVisible = isVideo
        
        stopProgressUpdate()
        if (isVideo) {
            startProgressUpdate()
        }
    }

    private fun startProgressUpdate() {
        updateProgressAction = object : Runnable {
            override fun run() {
                val videoView = getCurrentViewHolder()?.binding?.videoView
                if (videoView != null && videoView.isPlaying) {
                    binding.videoScrubber.max = videoView.duration
                    binding.videoScrubber.progress = videoView.currentPosition
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(updateProgressAction!!)
    }

    private fun stopProgressUpdate() {
        updateProgressAction?.let { handler.removeCallbacks(it) }
        updateProgressAction = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdate()
    }

    private fun shareMedia(item: MediaItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = when (item.type) {
                MediaType.IMAGE -> "image/*"
                MediaType.VIDEO -> "video/*"
                MediaType.PDF -> "application/pdf"
            }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share via"))
    }

    inner class MediaPagerAdapter(
        private var items: List<MediaItem>,
        private val onShortPress: () -> Unit
    ) : RecyclerView.Adapter<MediaPagerAdapter.ViewHolder>() {

        fun updateItems(newItems: List<MediaItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun getItem(position: Int) = items[position]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemViewerMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(val binding: ItemViewerMediaBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: MediaItem) {
                // Reset state from previous bind
                binding.videoView.stopPlayback()
                binding.photoView.setOnPhotoTapListener(null)
                binding.photoView.setOnOutsidePhotoTapListener(null)

                when (item.type) {
                    MediaType.IMAGE -> {
                        binding.photoView.isVisible = true
                        binding.videoContainer.isVisible = false
                        Glide.with(binding.photoView).load(item.uri).into(binding.photoView)
                        binding.photoView.setOnPhotoTapListener { _, _, _ -> onShortPress() }
                        binding.photoView.setOnOutsidePhotoTapListener { onShortPress() }
                    }
                    MediaType.VIDEO -> {
                        binding.photoView.isVisible = false
                        binding.videoContainer.isVisible = true
                        binding.btnPlayPause.isVisible = false
                        binding.videoView.setVideoURI(Uri.parse(item.uri))
                        binding.videoView.setOnPreparedListener { mp ->
                            mp.start()
                            binding.btnPlayPause.isVisible = false
                        }
                        binding.videoView.setOnErrorListener { _, _, _ ->
                            binding.btnPlayPause.isVisible = true
                            true
                        }
                        binding.videoContainer.setOnClickListener { onShortPress() }
                        binding.btnPlayPause.setOnClickListener {
                            binding.videoView.start()
                            binding.btnPlayPause.isVisible = false
                        }
                        binding.videoView.setOnClickListener {
                            if (binding.videoView.isPlaying) {
                                binding.videoView.pause()
                                binding.btnPlayPause.isVisible = true
                            } else {
                                binding.videoView.start()
                                binding.btnPlayPause.isVisible = false
                            }
                        }
                    }
                    MediaType.PDF -> {
                        // PDFs open externally from the gallery; if reached here via swipe, show placeholder
                        binding.photoView.isVisible = false
                        binding.videoContainer.isVisible = true
                        binding.btnPlayPause.isVisible = false
                        binding.tvError.isVisible = true
                        binding.tvError.text = "Tap to open PDF"
                        binding.videoContainer.setOnClickListener {
                            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(item.uri), "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                binding.root.context.startActivity(
                                    Intent.createChooser(openIntent, "Open PDF with")
                                )
                            } catch (e: Exception) { /* no PDF viewer installed */ }
                        }
                    }
                }
            }
        }
    }
}
