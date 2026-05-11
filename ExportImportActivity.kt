package com.example.rezeptmoment.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import com.example.rezeptmoment.R
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.RcpsExporter
import com.example.rezeptmoment.data.RcpsImporter
import com.example.rezeptmoment.data.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import com.example.rezeptmoment.ui.util.setProgressText


class ExportImportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"      // "import" | "export"
    }

    private lateinit var headline: TextView
    private lateinit var content: TextView
    private lateinit var icon: ImageView
    private lateinit var actionBtn: Button
    private lateinit var exportList: RecyclerView
    private lateinit var exportAdapter: ExportListAdapter
    private lateinit var db: AppDatabase
    private val isImportMode: Boolean get() = mode == "import"
    private var eligibleRecipes: List<Recipe> = emptyList()
    private val selectedRecipes = linkedSetOf<UUID>() // mirrors iOS selectedRecipes


    private var mode: String = "export" // default

    private lateinit var pickRcpsLauncher: ActivityResultLauncher<Intent>
    private lateinit var createRcpsLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_import)
        db = AppDatabase.getInstance(applicationContext)

        headline = findViewById(R.id.headline)
        content  = findViewById(R.id.content)
        icon     = findViewById(R.id.icon)
        actionBtn= findViewById(R.id.primaryAction)

        mode = intent.getStringExtra(EXTRA_MODE) ?: "export"
        setupToolbar()
        configureUiForMode()
        registerLaunchers()

        actionBtn.setOnClickListener {
            if (mode == "import") startImportFlow()
            else startExportFlow()
        }
        exportList = findViewById(R.id.exportList)
        exportAdapter = ExportListAdapter { id, checked ->  // <- update callback
            if (checked) selectedRecipes.add(id) else selectedRecipes.remove(id)
            updateExportCta()
        }
        exportList.layoutManager = LinearLayoutManager(this)
        exportList.adapter = exportAdapter

        if (!isImportMode) {
            exportList.visibility = View.VISIBLE
            lifecycleScope.launch {
                val all = db.recipeDao().getAllRecipes().first()
                eligibleRecipes = all.filter { it.belongsToCategoryId != null }
                selectedRecipes.clear()
                selectedRecipes.addAll(eligibleRecipes.map { it.uniqueId }) // default: all selected
                exportAdapter.submit(eligibleRecipes, selectedRecipes)
                updateExportCta()
            }
        } else {
            exportList.visibility = View.GONE
        }

        if (savedInstanceState == null) {
            when (intent?.action) {
                Intent.ACTION_VIEW -> {
                    intent.data?.let { dataUri ->
                        // Force import mode UI + run
                        mode = "import"
                        configureUiForMode()
                        exportList.visibility = View.GONE
                        handleImport(dataUri)
                    }
                }
                Intent.ACTION_SEND -> {
                    // Some file managers share as SEND with EXTRA_STREAM
                    val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    if (stream != null) {
                        mode = "import"
                        configureUiForMode()
                        exportList.visibility = View.GONE
                        handleImport(stream)
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (mode == "import") getString(R.string.import_recipes) else getString(R.string.export_recipes)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun configureUiForMode() {
        if (mode == "import") {
            headline.text = getString(R.string.import_recipes)
            content.text  = getString(R.string.import_recipes_hint) // “Import Recipes as .RCPS”
            actionBtn.text = getString(R.string.import_recipes)
            icon.setImageResource(R.drawable.ic_arrow_down)
        } else {
            headline.text = getString(R.string.export_recipes)
            content.text  = getString(R.string.export_recipes_hint) // “Export recipes to share…”
            actionBtn.text = getString(R.string.export_recipes)
            icon.setImageResource(R.drawable.ic_arrow_up)
        }
    }

    private fun registerLaunchers() {
        // Pick .rcps for import
        pickRcpsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            handleImport(uri, result.data) // pass the result Intent which contains the flags
        }

        // Create .rcps file for export
        createRcpsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            handleExport(uri)
        }
    }

    private fun updateExportCta() {
        if (!isImportMode) {
            val count = selectedRecipes.size
            actionBtn.text = resources.getQuantityString(
                R.plurals.export_with_count, count, count
            )
            actionBtn.isEnabled = count > 0
        }
    }

    /* ------------------- IMPORT ------------------- */

    private fun startImportFlow() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed"))
        }
        pickRcpsLauncher.launch(intent)
    }

    /**
     * Import a .rcps Uri.
     * @param uri the content Uri to read
     * @param sourceIntent the Intent that carried the Uri (may contain grant flags). Defaults to Activity.intent.
     */
    private fun handleImport(uri: Uri, sourceIntent: Intent? = intent) {
        // If the source intent had a persistable grant flag, try to take the persistable permission.
        // Use only READ/WRITE flags for takePersistableUriPermission (lint requires that).
        val hasPersistable = (sourceIntent?.flags ?: 0) and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        if (hasPersistable) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    // OR Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    // if you also need to write and the provider granted it
                )
            } catch (_: SecurityException) {
                // Provider refused persistent grant — ignore (we can still read via immediate grant)
            }
        }

        val name = queryDisplayName(uri)
        val mime = contentResolver.getType(uri).orEmpty()

        val looksRcps = when {
            name?.lowercase()?.endsWith(".rcps") == true -> true
            mime == "application/octet-stream" ||
                    mime == "application/zip" ||
                    mime == "application/x-zip-compressed" -> true
            else -> false
        }

        if (!looksRcps) {
            content.text = getString(R.string.invalid_import_type)
            return
        }

        setBusy(true, inProgressText = getString(R.string.importing))
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Empty file")
                val currentUserEmail = getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)
                    .getString("USER_EMAIL", "") ?: ""

                RcpsImporter.import(bytes, db, filesDir,
                    userId = currentUserEmail)

                EventBus.post(AppEvent.DidImportRecipes)

                setBusy(false)
                content.text = getString(R.string.import_done_stub)
                finish()
            } catch (e: Throwable) {
                setBusy(false)
                content.text = e.message ?: "Import failed"
            }
        }
    }


    /* ------------------- EXPORT ------------------- */

    private fun startExportFlow() {
        // Compute the set we plan to export (may be empty if data hasn't loaded yet)
        val chosenIds: Set<UUID> = if (selectedRecipes.isNotEmpty()) {
            selectedRecipes.toSet()
        } else {
            // fall back to whatever is currently in eligibleRecipes (may be empty initially)
            eligibleRecipes.map { it.uniqueId }.toSet()
        }

        // Guard: nothing to export
        if (chosenIds.isEmpty()) {
            // Optional: also disable the button to reinforce state
            actionBtn.isEnabled = false
            content.text = getString(R.string.nothing_to_export)
            return
        }

        val title = suggestRcpsFileName(chosenIds)

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, title) // safe, sanitized name
        }
        createRcpsLauncher.launch(intent)
    }

    private fun suggestRcpsFileName(chosenIds: Set<UUID>): String {
        val safe = { s: String ->
            s.replace(Regex("""[\\/:*?"<>|]"""), "_") // strip illegal path chars
                .take(150)
                .ifBlank { "Recipes" }
        }

        return if (chosenIds.size == 1) {
            // Try to find the selected recipe’s name from what's loaded
            val r = eligibleRecipes.firstOrNull { it.uniqueId in chosenIds }
            val base = safe(r?.primaryText ?: "Recipe")
            "$base.rcps"
        } else {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            "Recipes_${df.format(java.util.Date())}.rcps"
        }
    }

    private fun handleExport(uri: Uri) {
        setBusy(true, inProgressText = getString(R.string.exporting))

        lifecycleScope.launch {
            try {
                // Compute selected set; if empty, re-evaluate with the latest eligible list
                val chosenIds: Set<UUID> = if (selectedRecipes.isNotEmpty()) {
                    selectedRecipes.toSet()
                } else {
                    if (eligibleRecipes.isEmpty()) {
                        // Fallback: load once if user tapped before the initial load finished
                        val all = db.recipeDao().getAllRecipes().first()
                        eligibleRecipes = all.filter { it.belongsToCategoryId != null }
                    }
                    eligibleRecipes.map { it.uniqueId }.toSet()
                }

                if (chosenIds.isEmpty()) {
                    // No recipes to export – show a friendly message and bail
                    setBusy(false)
                    content.text = getString(R.string.nothing_to_export)
                    return@launch
                }

                contentResolver.openOutputStream(uri)?.use { out ->
                    RcpsExporter.export(
                        db = db,
                        filesDir = filesDir,
                        recipeIds = chosenIds,
                        outputStream = out,
                        onProgress = { msg ->
                            runOnUiThread {
                                actionBtn.setProgressText(R.string.exporting_with_progress, msg)

                            }
                        }
                    )
                }

                setBusy(false)
                content.text = getString(R.string.export_done_stub)
                finish()
            } catch (e: Throwable) {
                setBusy(false)
                content.text = e.message ?: "Export failed"
            }
        }
    }

    /* ------------------- Helpers ------------------- */

    private fun setBusy(busy: Boolean, inProgressText: String? = null) {
        actionBtn.isEnabled = !busy
        if (busy && inProgressText != null) actionBtn.text = inProgressText
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // update the Activity's intent

        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { dataUri ->
                    safeTakePersistablePermissionForUri(dataUri, intent)
                    mode = "import"
                    configureUiForMode()
                    exportList.visibility = View.GONE
                    handleImport(dataUri, intent) // pass the incoming intent that holds the flags
                }
            }
            Intent.ACTION_SEND -> {
                val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (stream != null) {
                    safeTakePersistablePermissionForUri(stream, intent)
                    mode = "import"
                    configureUiForMode()
                    exportList.visibility = View.GONE
                    handleImport(stream, intent) // pass the incoming intent that holds the flags
                }
            }
        }
    }

    fun AppCompatActivity.safeTakePersistablePermissionForUri(uri: Uri?, intent: Intent?) {
        if (uri == null || intent == null) return
        try {
            // Mask only the read/write grant bits the caller actually provided:
            val allowedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val takeFlags = intent.flags and allowedFlags
            if (takeFlags != 0) {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        } catch (se: SecurityException) {
            // Ignore / log, caller didn't grant persistable permission
            se.printStackTrace()
        }
    }


}
