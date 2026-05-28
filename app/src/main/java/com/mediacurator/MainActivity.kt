package com.anant.mediacurator

import android.Manifest
import android.app.Activity
import android.content.ContentValues
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anant.mediacurator.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var adapter: GalleryAdapter
    
    private var offeredOneClickDelete = false
    private var offeredAllFilesAccess = false  // show the PDF-access prompt only once per session

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                checkAllFilesAccessAndLoad()
            } else {
                showToast("Storage permission is required to view your media")
            }
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

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from the "All files access" settings screen.
            // If prefs are still empty, re-run the restore check now that we have file access
            // (the first attempt at init runs before this permission is granted).
            // Otherwise just reload so PDFs appear.
            if (hasAllFilesPermission() && viewModel.prefs.getDoneMonths().isEmpty()) {
                viewModel.checkAndAutoRestore()
            } else {
                viewModel.loadMedia()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            importHiddenMonths(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupSelectionBar()
        setupRestoreSpinners()
        setupFilterChips()
        setupScrollToTop()
        observeViewModel()
        requestPermissionsIfNeeded()
        // Pre-warm the AlertDialog machinery so the first real dialog opens instantly.
        // Android inflates dialog windows lazily; the first one takes ~300-600 ms.
        window.decorView.post {
            androidx.appcompat.app.AlertDialog.Builder(this).create().also { it.show(); it.dismiss() }
        }
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
                updateStickyHeader(firstVisible)
            }
        })
    }

    private fun setupFilterChips() {
        binding.chipPhoto.isChecked = viewModel.includePhoto.value ?: true
        binding.chipVideo.isChecked = viewModel.includeVideo.value ?: true
        binding.chipPdf.isChecked = viewModel.includePdf.value ?: true

        binding.chipPhoto.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.chipVideo.isChecked && !binding.chipPdf.isChecked) {
                binding.chipPhoto.isChecked = true
                return@setOnCheckedChangeListener
            }
            viewModel.setIncludePhoto(isChecked)
        }
        binding.chipVideo.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.chipPhoto.isChecked && !binding.chipPdf.isChecked) {
                binding.chipVideo.isChecked = true
                return@setOnCheckedChangeListener
            }
            viewModel.setIncludeVideo(isChecked)
        }
        binding.chipPdf.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.chipPhoto.isChecked && !binding.chipVideo.isChecked) {
                binding.chipPdf.isChecked = true
                return@setOnCheckedChangeListener
            }
            viewModel.setIncludePdf(isChecked)
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
            onYearToggle  = { year     -> viewModel.toggleYearExpansion(year) },
            onMonthToggle = { monthKey -> viewModel.toggleMonthExpansion(monthKey) },
            onMediaClick  = { item ->
                if (item.type == MediaType.PDF) {
                    val uri = Uri.parse(item.uri)
                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(openIntent, "Open PDF with"))
                    } catch (e: Exception) {
                        showToast("No PDF viewer found")
                    }
                } else {
                    val intent = Intent(this, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_START_ID, item.id)
                    }
                    startActivity(intent)
                }
            },
            onMonthHide = { group ->
                viewModel.markMonthDone(group.year, group.month)
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, "${group.label} hidden from this app", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("Undo") { viewModel.restoreMonth(group.year, group.month) }
                    .show()
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
            val totalBytes = adapter.getSelectedItems().sumOf { it.size }
            binding.tvSelectionCount.text = "$count selected · ${GalleryAdapter.fmtBytes(totalBytes)}"
            binding.btnDeleteSelected.isEnabled = true
        } else {
            binding.selectionBar.isVisible = false
        }
        invalidateOptionsMenu()
    }

    private fun setupRestoreSpinners() {
        binding.autoCompleteYear.setOnItemClickListener { parent, _, position, _ ->
            // Item is "2023 (450)" — extract just the year
            val selectedItem = parent.getItemAtPosition(position) as String
            val year = selectedItem.substringBefore(" ").trim()
            updateMonthsDropdown(year)
            binding.autoCompleteMonth.setText("", false)
            binding.autoCompleteMonth.showDropDown()
        }

        binding.autoCompleteMonth.setOnItemClickListener { parent, _, position, _ ->
            // Item is "Jan (45 / 2.3MB)" — extract the 3-char abbreviation, then match
            // against the full month label (e.g. "Jan" matches "January 2024").
            val abbr = (parent.getItemAtPosition(position) as String).substringBefore(" (")
            val yearStr = binding.autoCompleteYear.text.toString().substringBefore(" ").trim()
            val year = yearStr.toIntOrNull() ?: return@setOnItemClickListener

            val groups = viewModel.doneMonthsAvailable.value ?: emptyList()
            val group = groups.find { it.year == year && it.label.startsWith(abbr) }
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
            val abbrs = monthsInYear.map { it.label.split(" ")[0].take(3) }
            val stats = monthsInYear.map { group ->
                val size = fmtBytes(group.items.sumOf { it.size }).replace(" ", "")
                "${group.items.size} / $size"
            }
            val monthAdapter = object : android.widget.ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_dropdown_item_1line, abbrs
            ) {
                // MaterialAutoCompleteTextView wraps this adapter and routes
                // dropdown item creation through getView(), not getDropDownView().
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    val ctx = parent.context
                    // Resolve text colours from the current theme so this works
                    // on both dark and light themes.
                    val ta = ctx.theme.obtainStyledAttributes(
                        intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary)
                    )
                    val colorPrimary   = ta.getColor(0, android.graphics.Color.WHITE)
                    val colorSecondary = ta.getColor(1, 0xFFAAAAAA.toInt())
                    ta.recycle()

                    val ll = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(48, 24, 48, 24)
                    }
                    ll.addView(android.widget.TextView(ctx).apply {
                        text = abbrs[position]
                        textSize = 16f
                        setTextColor(colorPrimary)
                    })
                    ll.addView(android.widget.TextView(ctx).apply {
                        text = stats[position]
                        textSize = 13f
                        setTextColor(colorSecondary)
                    })
                    return ll
                }
            }
            binding.autoCompleteMonth.setAdapter(monthAdapter)
        }
    }

    /** Show the Unhide panel only when there are hidden months AND the tree view is active. */
    private fun updateRestoreLayoutVisibility() {
        val hasHidden  = (viewModel.doneMonthsAvailable.value?.isNotEmpty()) == true
        val isTreeMode = viewModel.sortMode.value != SortMode.SIZE_ABSOLUTE
        binding.restoreLayout.isVisible = hasHidden && isTreeMode
    }

    private fun observeViewModel() {
        viewModel.galleryItems.observe(this) { items ->
            adapter.submitList(items)
            binding.tvEmpty.isVisible = items.isEmpty()
            // Re-evaluate sticky after list changes (expansion toggles don't always fire a scroll event)
            binding.recyclerView.post { updateStickyHeader() }
        }
        
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.isVisible = loading
        }
        
        viewModel.sortMode.observe(this) { mode ->
            invalidateOptionsMenu()
            supportActionBar?.subtitle = when (mode) {
                SortMode.DATE_NEWEST       -> "Newest first"
                SortMode.DATE_OLDEST       -> "Oldest first"
                SortMode.SIZE_ABSOLUTE     -> "Largest first (overall)"
                SortMode.SIZE_WITHIN_MONTH -> "Largest first (per month)"
                SortMode.COUNT_PER_MONTH   -> "Most items first"
            }
            // Unhide panel is a tree-view operation — hide it in flat mode
            updateRestoreLayoutVisibility()
        }

        viewModel.storageSavedEvent.observe(this) { bytes ->
            val text = when {
                bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
                else                    -> "$bytes B"
            }
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "Freed $text", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
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
                updateRestoreLayoutVisibility()

                // Total hidden count
                val totalHidden = groups.sumOf { it.items.size }
                binding.tvHiddenTotal.text = "$totalHidden items hidden"

                // Year items with per-year counts and sizes: "2023 (450 / 10 MB)"
                val yearGroups = groups.groupBy { it.year }
                val yearCountMap = yearGroups.mapValues { (_, months) -> months.sumOf { it.items.size } }
                val yearSizeMap  = yearGroups.mapValues { (_, months) -> months.sumOf { g -> g.items.sumOf { it.size } } }
                val yearItems = groups.map { it.year }.distinct().sortedDescending()
                    .map { y -> "$y (${yearCountMap[y] ?: 0} / ${fmtBytes(yearSizeMap[y] ?: 0L)})" }
                    .toTypedArray()
                binding.autoCompleteYear.setSimpleItems(yearItems)

                // Re-populate month dropdown if a year is already selected
                val currentYear = binding.autoCompleteYear.text.toString().substringBefore(" ").trim()
                if (currentYear.isNotEmpty()) {
                    updateMonthsDropdown(currentYear)
                } else {
                    binding.menuMonth.isEnabled = false
                }
            }
        }

        viewModel.totalPhotos.observe(this) { binding.tvTotalPhotos.text = "Photos: $it" }
        viewModel.totalVideos.observe(this) { binding.tvTotalVideos.text = "Videos: $it" }
        viewModel.totalPdfs.observe(this)   {
            binding.tvTotalPdfs.text = "PDFs: $it"
            binding.tvTotalPdfs.isVisible = it > 0
        }

        viewModel.mediaStats.observe(this) { /* stored for popup; nothing to update in stats bar */ }

        viewModel.autoRestorePrompt.observe(this) { months ->
            if (months == null) return@observe
            val count = months.size
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Backup found")
                .setMessage(
                    "Found ‘${ GalleryViewModel.AUTO_BACKUP_FILENAME}’ in Downloads " +
                    "with $count hidden month${if (count == 1) "" else "s"}.\n\nImport it?"
                )
                .setPositiveButton("Import") { _, _ -> viewModel.confirmAutoRestore(months) }
                .setNegativeButton("Skip")   { _, _ -> viewModel.dismissAutoRestore() }
                .setCancelable(false)
                .show()
        }

        binding.btnStatsInfo.setOnClickListener { showStatsDialog() }

        viewModel.scrollToTopEvent.observe(this) {
            binding.recyclerView.scrollToPosition(0)
            binding.appBarLayout.setExpanded(true, false)
        }

        viewModel.scrollToMonthKey.observe(this) { monthKey ->
            if (monthKey == null) return@observe
            val pos = adapter.currentList.indexOfFirst {
                it is GalleryItem.Header && it.monthKey == monthKey
            }
            if (pos >= 0) {
                (binding.recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, 0)
                binding.appBarLayout.setExpanded(true, false)
            }
            viewModel.clearScrollToMonth()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val inSelection = adapter.selectionMode
        menu.findItem(R.id.action_sort)?.isVisible = !inSelection
        // With checkableBehavior="single", only set the target item to true —
        // the group automatically unchecks all siblings. Setting others to false
        // explicitly triggers unexpected exclusive-group side-effects.
        val mode = viewModel.sortMode.value ?: SortMode.DATE_OLDEST
        val sortSub = menu.findItem(R.id.action_sort)?.subMenu
        if (sortSub != null) {
            val targetId = when (mode) {
                SortMode.DATE_NEWEST       -> R.id.sort_newest
                SortMode.DATE_OLDEST       -> R.id.sort_oldest
                SortMode.SIZE_ABSOLUTE     -> R.id.sort_size_absolute
                SortMode.SIZE_WITHIN_MONTH -> R.id.sort_size_month
                SortMode.COUNT_PER_MONTH   -> R.id.sort_count_month
            }
            sortSub.findItem(targetId)?.isChecked = true
        }
        menu.findItem(R.id.action_refresh)?.isVisible = !inSelection
        
        val oneClickItem = menu.findItem(R.id.action_one_click_delete)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            oneClickItem.isVisible = !inSelection
            oneClickItem.isChecked = hasManageMediaPermission()
        } else {
            oneClickItem.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.sort_newest        -> { viewModel.setSortMode(SortMode.DATE_NEWEST);       true }
        R.id.sort_oldest        -> { viewModel.setSortMode(SortMode.DATE_OLDEST);       true }
        R.id.sort_size_absolute -> { viewModel.setSortMode(SortMode.SIZE_ABSOLUTE);     true }
        R.id.sort_size_month    -> { viewModel.setSortMode(SortMode.SIZE_WITHIN_MONTH); true }
        R.id.sort_count_month   -> { viewModel.setSortMode(SortMode.COUNT_PER_MONTH);   true }
        R.id.action_refresh -> { viewModel.loadMedia(forceRefresh = true); true }
        R.id.action_one_click_delete -> { requestManageMediaPermission(); true }
        R.id.action_backup -> {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Hidden months backup")
                .setItems(arrayOf("Export to Downloads", "Import from file")) { _, which ->
                    if (which == 0) exportHiddenMonths()
                    else importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        R.id.action_help -> { showHelpDialog(); true }
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
                // Can't revoke programmatically — send user to App Settings to do it there
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
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

    private fun hasAllFilesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()

    /**
     * Load media immediately (photos/videos don't need broad storage access).
     * If MANAGE_EXTERNAL_STORAGE is not granted on Android 11+, show a one-time
     * prompt explaining that PDFs won't be visible without it.
     */
    private fun checkAllFilesAccessAndLoad() {
        viewModel.loadMedia()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !hasAllFilesPermission()
            && !offeredAllFilesAccess
        ) {
            offeredAllFilesAccess = true
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("PDF access")
                .setMessage(
                    "To browse PDF files, Media Curator needs the \"All files access\" permission.\n\n" +
                    "Photos and videos work without it. Tap Grant to enable PDFs."
                )
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    allFilesAccessLauncher.launch(intent)
                }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1_024L         -> "%.1f KB".format(b / 1_024.0)
        else                -> "$b B"
    }

    private fun showStatsDialog() {
        val s = viewModel.mediaStats.value
        if (s == null) { showToast("Stats not loaded yet"); return }

        fun row(label: String, vis: Int, hid: Int, tot: Int) =
            "%-8s %5d + %5d = %5d".format(label, vis, hid, tot)
        fun rowB(label: String, vb: Long, hb: Long) =
            "%-8s %s + %s = %s".format(label, fmtBytes(vb), fmtBytes(hb), fmtBytes(vb + hb))

        val vAll = s.visiblePhotos + s.visibleVideos + s.visiblePdfs
        val hAll = s.hiddenPhotos  + s.hiddenVideos  + s.hiddenPdfs
        val tAll = s.totalPhotos   + s.totalVideos   + s.totalPdfs
        val vbAll = s.visiblePhotoBytes + s.visibleVideoBytes + s.visiblePdfBytes
        val hbAll = s.hiddenPhotoBytes  + s.hiddenVideoBytes  + s.hiddenPdfBytes

        val msg = buildString {
            appendLine("COUNTS  (visible + hidden = total)")
            appendLine(row("Photos",  s.visiblePhotos, s.hiddenPhotos, s.totalPhotos))
            appendLine(row("Videos",  s.visibleVideos, s.hiddenVideos, s.totalVideos))
            appendLine(row("PDFs",    s.visiblePdfs,   s.hiddenPdfs,   s.totalPdfs))
            appendLine(row("All",     vAll, hAll, tAll))
            appendLine()
            appendLine("SIZES   (visible + hidden = total)")
            appendLine(rowB("Photos",  s.visiblePhotoBytes, s.hiddenPhotoBytes))
            appendLine(rowB("Videos",  s.visibleVideoBytes, s.hiddenVideoBytes))
            appendLine(rowB("PDFs",    s.visiblePdfBytes,   s.hiddenPdfBytes))
            appendLine(rowB("All",     vbAll, hbAll))
            appendLine()
            append(s.integrityDetail)
        }

        val tv = android.widget.TextView(this).apply {
            text = msg
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(48, 32, 48, 16)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Media Stats")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .show()
    }

    // ── Sticky header ─────────────────────────────────────────────────────────

    private fun updateStickyHeader(firstVisiblePos: Int = -1) {
        // Flat mode has no tree headers — nothing to show.
        if (viewModel.sortMode.value == SortMode.SIZE_ABSOLUTE) {
            binding.stickyHeader.isVisible = false
            return
        }

        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstPos = if (firstVisiblePos >= 0) firstVisiblePos
                       else lm.findFirstVisibleItemPosition()
        if (firstPos < 0) { binding.stickyHeader.isVisible = false; return }

        val list      = adapter.currentList
        val firstItem = list.getOrNull(firstPos)

        // Walk back to find the nearest YearHeader and Header above the viewport top
        var yearCtx:  GalleryItem.YearHeader? = null
        var monthCtx: GalleryItem.Header?     = null
        for (i in firstPos downTo 0) {
            val item = list.getOrNull(i) ?: break
            if (monthCtx == null && item is GalleryItem.Header) monthCtx = item
            if (item is GalleryItem.YearHeader) { yearCtx = item; break }
        }

        // Hide when no year context, or the year header itself is already at the top
        if (yearCtx == null || firstItem is GalleryItem.YearHeader) {
            binding.stickyHeader.isVisible = false
            return
        }

        binding.stickyHeader.isVisible = true

        // Month row — only when inside a month (month header itself has scrolled off top)
        val showMonth = monthCtx != null && firstItem !is GalleryItem.Header
        binding.stickyMonthRow.isVisible = showMonth

        // Year row — always shown while sticky is visible.
        // Behaviour: "go up one level."
        //   • Inside an open month  → tap closes the MONTH, keeping the year expanded
        //   • Browsing month headers → tap closes the YEAR
        binding.tvStickyYearArrow.text = if (yearCtx.isExpanded) "▼" else "▶"
        binding.tvStickyYear.text      = yearCtx.year.toString()
        binding.tvStickyYearStats.text = GalleryAdapter.formatTypeBreakdown(yearCtx.photoCount, yearCtx.videoCount, yearCtx.pdfCount, yearCtx.totalBytes)
        val capturedYear = yearCtx.year
        if (showMonth && monthCtx != null) {
            val capturedKey = monthCtx.monthKey
            binding.stickyYearRow.setOnClickListener { viewModel.toggleMonthExpansion(capturedKey) }
        } else {
            binding.stickyYearRow.setOnClickListener { viewModel.toggleYearExpansion(capturedYear) }
        }

        if (showMonth && monthCtx != null) {
            binding.tvStickyMonthArrow.text = if (monthCtx.isExpanded) "▼" else "▶"
            binding.tvStickyMonth.text      = monthCtx.label
            binding.tvStickyMonthStats.text = GalleryAdapter.formatTypeBreakdown(monthCtx.photoCount, monthCtx.videoCount, monthCtx.pdfCount, monthCtx.totalBytes)
            val capturedKey = monthCtx.monthKey
            binding.stickyMonthRow.setOnClickListener { viewModel.toggleMonthExpansion(capturedKey) }
        }
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    private fun exportHiddenMonths() {
        val months = viewModel.prefs.getDoneMonths()
        if (months.isEmpty()) {
            showToast("No hidden months to export")
            return
        }
        lifecycleScope.launch {
            try {
                val stamp    = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                val filename = "mediacurator_hidden_$stamp.json"
                val json     = buildExportJson(months)

                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.getContentUri("external"), values
                        ) ?: throw Exception("Could not create file in Downloads")
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        File(dir, filename).writeText(json, Charsets.UTF_8)
                    }
                }
                showToast("Exported ${months.size} hidden months → Downloads/$filename")
            } catch (e: Exception) {
                Log.e("MainActivity", "Export failed", e)
                showToast("Export failed: ${e.message}")
            }
        }
    }

    private fun buildExportJson(months: Set<String>): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val arr = months.sorted().joinToString(",\n    ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"exportedAt\": \"$ts\",\n  \"hiddenMonths\": [\n    $arr\n  ]\n}"
    }

    private fun importHiddenMonths(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: run { showToast("Could not read file"); return@launch }

                val obj      = org.json.JSONObject(json)
                val arr      = obj.getJSONArray("hiddenMonths")
                val incoming = (0 until arr.length()).map { arr.getString(it) }.toSet()

                val existing = viewModel.prefs.getDoneMonths()
                val newCount = (incoming - existing).size
                viewModel.prefs.setDoneMonths(existing + incoming)
                viewModel.loadMedia(forceRefresh = false)

                showToast("Import done — $newCount new months added (${existing.size + newCount} total hidden)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Import failed", e)
                showToast("Import failed: ${e.message}")
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── Help ──────────────────────────────────────────────────────────────────

    private fun showHelpDialog() {
        val text = """
Systematic, month-by-month curation of a large media library - all without losing your place. This is the key feature of the app.

Most gallery apps show everything at once. This one lets you work through your library like a task list: open a year, open a month, delete what you don’t want, then hide the month when you’re done.

Hidden items stay out of the way within the app but aren’t deleted - they remain fully accessible in your phone’s regular gallery. Should you wish, you can unhide them anytime you want as well. Auto-backup preserves your progress across reinstalls and device transfers.

Built for people with years of accumulated photos, videos, and PDFs who want to clean up methodically - not just browse.
─────────────────────────────
📂 BROWSING (Tree View)
Years are shown collapsed. Tap a year to expand it and see its months. Tap a month to see its items. Tap again to collapse.

📌 STICKY HEADER
While scrolling inside a year/month, a floating bar stays pinned at the top showing where you are. Tap it to collapse the open month (or the year, if no month is open).

🙈 HIDING A MONTH
Scroll to the bottom of any open month and tap "Hide this month". The month disappears from your gallery — files are NOT deleted, just hidden from this app.

👁️ UNHIDING
When you have hidden months, a bar appears at the top. Pick a year and month to restore it instantly. The app scrolls straight to it.

🗑️ DELETING FILES
Long-press any item to enter multi-select mode. Tap more items to add them to the selection. Tap Delete in the bar at the bottom.

⚡ ONE-CLICK DELETE (menu)
When enabled, a single tap on any item deletes it immediately — no confirmation. Use with care.

🔢 SORT OPTIONS (menu → Sort)
• Newest / Oldest first — by capture date
• Largest first (overall) — biggest files first, across all months
• Largest first (per month) — months ordered by their total size
• Most items first — months with the most files appear at the top

💾 BACKUP (menu → Export / Import)
Hidden-month state is auto-saved to mediacurator_hidden.json in your Downloads folder after every hide/unhide. It survives app-data clears and reinstalls — just reinstall and the state restores automatically. Use Export / Import to move your state to another device.

ℹ️ Note: mediacurator_hidden.json is NOT deleted when you uninstall the app. Delete it manually from your Downloads folder if you want a completely clean slate.
        """.trimIndent()

        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize  = 13f
            setLineSpacing(4f, 1f)
            setPadding(56, 32, 56, 24)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Media Curator — Help")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Got it", null)
            .show()
    }
}
