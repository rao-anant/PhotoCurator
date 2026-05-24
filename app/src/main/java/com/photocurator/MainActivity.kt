package com.photocurator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photocurator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter
    
    private var offeredOneClickDelete = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                checkAllFilesAccessAndLoad()
            } else {
                showToast("Storage permission is required to view your media")
            }
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.loadMedia(forceRefresh = true)
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            viewModel.onDeletePermissionResult(result.resultCode == Activity.RESULT_OK)
        }

    private val manageMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            invalidateOptionsMenu()
            showToast(if (hasManageMediaPermission()) "One-Click Delete enabled" else "One-Click Delete disabled")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupSelectionBar()
        setupRestoreSpinners()
        setupPdfCheckbox()
        setupScrollToTop()
        observeViewModel()
        requestPermissionsIfNeeded()
    }

    private fun setupScrollToTop() {
        binding.fabScrollToTop.setOnClickListener {
            binding.recyclerView.scrollToPosition(0)
            binding.appBarLayout.setExpanded(true, true)
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
                binding.fabScrollToTop.isVisible = firstVisible > 15
            }
        })
    }

    private fun setupPdfCheckbox() {
        binding.cbIncludePdf.isChecked = viewModel.includePdf.value ?: false
        
        binding.cbIncludePdf.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setIncludePdf(isChecked)
            binding.tvTotalPdfs.isVisible = isChecked
            
            if (isChecked && !hasAllFilesPermission()) {
                requestAllFilesPermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        // We ALWAYS trigger a refresh on resume. The ViewModel handles caching and 
        // immediate filtering of deleted items using the static flight set.
        // This is necessary because the MediaViewerActivity may have deleted files.
        viewModel.loadMedia(forceRefresh = true)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (adapter.selectionMode) exitSelectionMode()
        else super.onBackPressed()
    }

    private fun setupRecyclerView() {
        val spanCount = 4
        adapter = GalleryAdapter(
            onMediaClick = { item ->
                val intent = Intent(this, MediaViewerActivity::class.java).apply {
                    putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
                }
                startActivity(intent)
            },
            onMonthHide = { group -> 
                viewModel.markMonthDone(group.year, group.month)
                showToast("${group.label} hidden")
            },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = GallerySpanSizeLookup(adapter, spanCount)
        
        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            this.adapter = this@MainActivity.adapter
            setHasFixedSize(false)
            setItemViewCacheSize(30) 
        }
    }

    private fun setupSelectionBar() {
        binding.btnDeleteSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) { showToast("No items selected"); return@setOnClickListener }
            exitSelectionMode()
            viewModel.deleteMedia(selected)
        }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
    }

    private fun exitSelectionMode() {
        adapter.exitSelectionMode()
        binding.selectionBar.isVisible = false
        invalidateOptionsMenu()
    }

    private fun updateSelectionBar(count: Int) {
        if (count > 0) {
            if (!binding.selectionBar.isVisible) {
                binding.selectionBar.isVisible = true
            }
            binding.tvSelectionCount.text = "$count selected"
            binding.btnDeleteSelected.isEnabled = true
        } else {
            binding.selectionBar.isVisible = false
        }
        invalidateOptionsMenu()
    }

    private fun setupRestoreSpinners() {
        binding.autoCompleteYear.setOnItemClickListener { parent, _, position, _ ->
            val selectedYear = parent.getItemAtPosition(position) as String
            updateMonthsDropdown(selectedYear)
            binding.autoCompleteMonth.setText("", false)
            binding.autoCompleteMonth.showDropDown()
        }

        binding.autoCompleteMonth.setOnItemClickListener { parent, _, position, _ ->
            val selectedMonthLabel = parent.getItemAtPosition(position) as String
            val yearStr = binding.autoCompleteYear.text.toString()
            val year = yearStr.toIntOrNull() ?: return@setOnItemClickListener
            
            val groups = viewModel.doneMonthsAvailable.value ?: emptyList()
            val group = groups.find { it.year == year && it.label.startsWith(selectedMonthLabel) }
            group?.let {
                viewModel.restoreMonth(it.year, it.month)
                binding.autoCompleteMonth.setText("", false)
            }
        }
    }

    private fun updateMonthsDropdown(year: String) {
        val groups = viewModel.doneMonthsAvailable.value ?: emptyList()
        val monthsInYear = groups.filter { it.year.toString() == year }
        
        if (monthsInYear.isEmpty()) {
            binding.menuMonth.isEnabled = false
        } else {
            binding.menuMonth.isEnabled = true
            binding.menuMonth.isVisible = true
            val monthLabels = monthsInYear.map { it.label.split(" ")[0] }.toTypedArray()
            binding.autoCompleteMonth.setSimpleItems(monthLabels)
        }
    }

    private fun observeViewModel() {
        viewModel.galleryItems.observe(this) { items ->
            adapter.submitList(items)
            binding.tvEmpty.isVisible = items.isEmpty()
        }
        
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.isVisible = loading
        }
        
        viewModel.sortAscending.observe(this) { asc ->
            invalidateOptionsMenu()
            supportActionBar?.subtitle = if (asc) "Oldest first" else "Newest first"
        }
        
        viewModel.deletePermissionRequest.observe(this) { intentSender ->
            intentSender?.let {
                deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
            }
        }
        
        viewModel.doneMonthsAvailable.observe(this) { groups ->
            if (groups.isEmpty()) {
                binding.restoreLayout.isVisible = false
            } else {
                binding.restoreLayout.isVisible = true
                val years = groups.map { it.year.toString() }.distinct().sortedDescending().toTypedArray()
                binding.autoCompleteYear.setSimpleItems(years)
                
                val currentYear = binding.autoCompleteYear.text.toString()
                if (currentYear.isNotEmpty()) {
                    updateMonthsDropdown(currentYear)
                } else {
                    binding.menuMonth.isEnabled = false
                }
            }
        }

        viewModel.totalPhotos.observe(this) { count ->
            binding.tvTotalPhotos.text = "Photos: $count"
        }
        viewModel.totalVideos.observe(this) { count ->
            binding.tvTotalVideos.text = "Videos: $count"
        }
        viewModel.totalPdfs.observe(this) { count ->
            binding.tvTotalPdfs.text = "PDFs: $count"
            binding.tvTotalPdfs.isVisible = viewModel.includePdf.value ?: false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val asc = viewModel.sortAscending.value ?: true
        val inSelection = adapter.selectionMode
        menu.findItem(R.id.action_sort)?.apply {
            title = if (asc) "Newest first" else "Oldest first"
            isVisible = !inSelection
        }
        menu.findItem(R.id.action_refresh)?.isVisible = !inSelection
        
        val oneClickItem = menu.findItem(R.id.action_one_click_delete)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            oneClickItem.isVisible = !inSelection
            oneClickItem.title = if (hasManageMediaPermission()) "One-Click Delete: ON" else "Enable One-Click Delete"
        } else {
            oneClickItem.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_sort -> { viewModel.toggleSort(); true }
        R.id.action_refresh -> { viewModel.loadMedia(forceRefresh = true); true }
        R.id.action_one_click_delete -> { requestManageMediaPermission(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun hasManageMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaStore.canManageMedia(this)
        } else false
    }

    private fun requestManageMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (hasManageMediaPermission()) {
                showToast("One-Click Delete is already enabled")
            } else {
                val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageMediaLauncher.launch(intent)
            }
        }
    }

    private fun hasBasicPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        // On Android 13+, we don't strictly require READ_EXTERNAL_STORAGE as it returns false
        // even if media permissions are granted.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            // Still requested for PDF access on some devices, though often denied for media apps
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = getRequiredPermissions()
        if (hasBasicPermissions()) {
            checkAllFilesAccessAndLoad()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun checkAllFilesAccessAndLoad() {
        if (viewModel.includePdf.value == true && !hasAllFilesPermission()) {
            requestAllFilesPermission()
        } else {
            viewModel.loadMedia()
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                allFilesAccessLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessLauncher.launch(intent)
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
