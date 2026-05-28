package com.anant.mediacurator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.anant.mediacurator.databinding.ActivityPhotoViewerBinding

class PhotoViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhotoViewerBinding

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_MEDIA_NAME = "extra_media_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val uri = intent.getStringExtra(EXTRA_MEDIA_URI) ?: run { finish(); return }
        supportActionBar?.title = intent.getStringExtra(EXTRA_MEDIA_NAME) ?: ""
        Glide.with(this).load(uri).into(binding.photoView)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}