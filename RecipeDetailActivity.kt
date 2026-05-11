package com.example.rezeptmoment.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.RecipeElement
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withContext
import com.example.rezeptmoment.ui.util.attachIngredientValidation
import com.example.rezeptmoment.ui.util.saveImageToFilesDir
import com.example.rezeptmoment.ui.util.toFloatLenient
import kotlinx.coroutines.flow.firstOrNull
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.example.rezeptmoment.CreateRecipeActivity
import com.example.rezeptmoment.RecipeElementAdapter



class RecipeDetailActivity : AppCompatActivity() {

    private lateinit var adapter: RecipeElementAdapter
    private var recipeId: UUID? = null
    private var pendingHeaderImageChange = false
    private var currentPeople: Long? = null
    private var selectedIngredient: RecipeElement? = null
    private lateinit var ingredientAdapter: RecipeElementAdapter
    private lateinit var stepAdapter: RecipeElementAdapter
    private var ingredientsExpanded = true
    private var instructionsExpanded = true
    private var attachmentsExpanded = true
    private lateinit var attachmentsListView: ListView
    private lateinit var attachmentsSection: LinearLayout
    private lateinit var attachmentsAdapter: ArrayAdapter<String>
    private val attachmentItems = mutableListOf<String>()
    private var currentServings: Long = 1
    private val ingredientsOnShoppingList = mutableSetOf<UUID>()


    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 24)
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        handlePickedImages(uris)
    }
    private val pickMultipleImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        handlePickedImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_recipe_detail)

        // Toolbar setup
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbarRecipeDetail)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.recipes)

        // View references
        val spinnerCategory = findViewById<Spinner>(R.id.spinnerCategory)
        val ingredientsRecycler = findViewById<RecyclerView>(R.id.ingredientsRecycler)
        val stepsRecycler = findViewById<RecyclerView>(R.id.stepsRecycler)

        val servingsMinus = findViewById<ImageButton>(R.id.servingsMinusDetail)
        val servingsPlus = findViewById<ImageButton>(R.id.servingsPlusDetail)
        val servingsCount = findViewById<TextView>(R.id.servingsCountDetail)

        servingsMinus.isEnabled = false
        servingsPlus.isEnabled = false

        servingsMinus.setOnClickListener {
            if (currentServings > 1) {
                currentServings--
                servingsCount.text = currentServings.toString()
                updateIngredientListScaling()

            }
        }
        servingsPlus.setOnClickListener {
            currentServings++
            servingsCount.text = currentServings.toString()
            updateIngredientListScaling()

        }

        // Expand/collapse logic
        val ingredientsHeader = findViewById<LinearLayout>(R.id.ingredientsExpandableHeader)
        val ingredientsIcon = findViewById<ImageView>(R.id.ingredientsExpandableIcon)
        val instructionsHeader = findViewById<LinearLayout>(R.id.instructionsExpandableHeader)
        val instructionsIcon = findViewById<ImageView>(R.id.instructionsExpandableIcon)
        attachmentsSection = findViewById(R.id.attachmentsExpandableHeader)
        val attachmentsHeader = attachmentsSection
        val attachmentsIcon = findViewById<ImageView>(R.id.attachmentsExpandableIcon)
        attachmentsListView = findViewById(R.id.attachmentsDetailListView)
        attachmentsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, attachmentItems)
        attachmentsListView.adapter = attachmentsAdapter

        // Expansion state initialization (ensure these vars exist and default to 'true')
        ingredientsIcon.setImageResource(
            if (ingredientsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
        )
        instructionsIcon.setImageResource(
            if (instructionsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
        )
        attachmentsIcon.setImageResource(
            if (attachmentsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
        )
        ingredientsRecycler.visibility = if (ingredientsExpanded) View.VISIBLE else View.GONE
        stepsRecycler.visibility = if (instructionsExpanded) View.VISIBLE else View.GONE
        attachmentsListView.visibility = if (attachmentsExpanded) View.VISIBLE else View.GONE

        ingredientsHeader.setOnClickListener {
            ingredientsExpanded = !ingredientsExpanded
            ingredientsRecycler.visibility = if (ingredientsExpanded) View.VISIBLE else View.GONE
            ingredientsIcon.setImageResource(
                if (ingredientsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
            )
        }
        instructionsHeader.setOnClickListener {
            instructionsExpanded = !instructionsExpanded
            stepsRecycler.visibility = if (instructionsExpanded) View.VISIBLE else View.GONE
            instructionsIcon.setImageResource(
                if (instructionsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
            )
        }
        attachmentsHeader.setOnClickListener {
            attachmentsExpanded = !attachmentsExpanded
            attachmentsListView.visibility = if (attachmentsExpanded) View.VISIBLE else View.GONE
            attachmentsIcon.setImageResource(
                if (attachmentsExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
            )
        }

        attachmentsListView.setOnItemClickListener { _, _, position, _ ->
            val db = AppDatabase.getInstance(this)
            lifecycleScope.launch {
                val elements = db.recipeElementDao().getElementsForRecipe(recipeId!!).firstOrNull() ?: return@launch
                val attachments = elements.filter {
                    it.instructionType == "link"
                            || it.instructionType == "pdf"
                            || (it.instructionType == "image" && it.attachmentType == "pdf")
                }
                val el = attachments.getOrNull(position) ?: return@launch
                when (el.instructionType) {
                    "link" -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(el.primaryText))
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                this@RecipeDetailActivity,
                                getString(R.string.no_app_to_open_file),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    "pdf" -> {
                        android.widget.Toast.makeText(
                            this@RecipeDetailActivity,
                            "PDF opening is not implemented.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        android.widget.Toast.makeText(
                            this@RecipeDetailActivity,
                            getString(R.string.no_app_to_open_file),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // RecyclerView adapters (readonly)
        ingredientAdapter = RecipeElementAdapter(
            onIngredientClicked ={ /* No-op in detail */ },
            onEditClicked = { /* No-op in detail */ },
            onDeleteClicked = { /* No-op in detail */ },
            onAddToShoppingList = { ingredient->
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(this@RecipeDetailActivity)
                    val upcomingDao = db.upcomingDao()
                    val ingredientId = ingredient.uniqueId ?: return@launch

                    val existing = upcomingDao.getByIngredientId(ingredientId)

                    if (existing.isNotEmpty()) {
                        upcomingDao.deleteUpcomingForIngredientIds(listOf(ingredientId))
                        ingredientsOnShoppingList.remove(ingredientId)

                        withContext(Dispatchers.Main) {ingredientAdapter.updateShoppingStateFor(ingredientId, false)
                            android.widget.Toast.makeText(
                                this@RecipeDetailActivity,
                                getString(R.string.removed_shopping_item),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()}
                    } else {
                        val upcoming = com.example.rezeptmoment.data.UpcomingObject(
                            uniqueId = java.util.UUID.randomUUID(),
                            belongsToRecipeId = ingredient.belongsToRecipeId,
                            linkedIngredientId = ingredientId,
                            isMarkedAsComplete = false,
                            quantity = ingredient.quantity,
                            primaryText = ingredient.primaryText ?: "",
                            secondaryText = ingredient.secondaryText.orEmpty(),
                            orderingIndex = System.currentTimeMillis(),
                            type = "shoppingIngredient"
                        )
                        upcomingDao.insertUpcomingObject(upcoming)
                        ingredientsOnShoppingList.add(ingredientId)

                        withContext(Dispatchers.Main) {
                            ingredientAdapter.updateShoppingStateFor(ingredientId, true)
                            android.widget.Toast.makeText(
                                this@RecipeDetailActivity,
                                getString(R.string.added_to_shopping),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    com.example.rezeptmoment.ui.util.EventBus.post(AppEvent.DidUpdateShoppingList)
                }
            }
        )


        ingredientsRecycler.adapter = ingredientAdapter
        ingredientsRecycler.layoutManager = LinearLayoutManager(this)

        stepAdapter = RecipeElementAdapter(
            onMediaClicked = { openMedia(it) }
        )
        stepsRecycler.adapter = stepAdapter
        stepsRecycler.layoutManager = LinearLayoutManager(this)

        // Retrieve recipe ID from intent
        recipeId = intent.getStringExtra("recipeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (recipeId == null) {
            finish()
            return
        }

        val db = AppDatabase.getInstance(this)

        // Category spinner (readonly)
        lifecycleScope.launch {
            val categories = db.recipeCategoryDao().getAllCategories().firstOrNull().orEmpty()
            val categoryNames = categories.map { it.primaryText }
            val adapter = ArrayAdapter(
                this@RecipeDetailActivity,
                android.R.layout.simple_spinner_item,
                categoryNames
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerCategory.adapter = adapter
            spinnerCategory.isEnabled = false
            val recipe = db.recipeDao().getRecipeById(recipeId!!) ?: return@launch
            val idx = categories.indexOfFirst { it.uniqueId == recipe.belongsToCategoryId }
            if (idx >= 0) spinnerCategory.setSelection(idx)
            spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {}
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }

        // Load all data
        loadRecipe(recipeId!!)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_edit -> {
                recipeId?.let { id ->
                    val intent = Intent(this, CreateRecipeActivity::class.java)
                    intent.putExtra("recipeId", recipeId.toString())
                    startActivity(intent)
                }
                return true
            }
            R.id.action_delete -> {
                recipeId?.let { id ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.delete_recipe)
                        .setMessage(R.string.delete_recipe_confirm)
                        .setPositiveButton(R.string.delete) { d, _ ->
                            lifecycleScope.launch {
                                val db = AppDatabase.getInstance(this@RecipeDetailActivity)
                                db.recipeDao().deleteRecipeById(id)
                                finish()
                            }
                            d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_recipe_detail, menu)
        return true
    }

    override fun onResume() {
        super.onResume()
        recipeId?.let { loadRecipe(it) }
    }

    private fun loadRecipe(recipeId: UUID) {
        val db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            val recipe = db.recipeDao().getRecipeById(recipeId) ?: run { finish(); return@launch }
            currentServings = recipe.numberOfPersons ?: 1L
            findViewById<TextView>(R.id.servingsCountDetail).text = currentServings.toString()
            ingredientAdapter.currentServings = currentServings
            findViewById<TextView>(R.id.titleTextRecipeDetail).text = recipe.primaryText
            db.recipeElementDao().getElementsForRecipe(recipeId).collect { elements ->
                val ingredients = elements.filter { it.type == "ingredients" }
                val steps = elements.filter { it.type == "steps" }
                // compute which ingredients are on the shopping list
                val upcomingDao = db.upcomingDao()
                ingredientsOnShoppingList.clear()
                for (ingredient in ingredients) {
                    val id = ingredient.uniqueId ?: continue
                    val existing = upcomingDao.getByIngredientId(id)
                    if (existing.isNotEmpty()) {
                        ingredientsOnShoppingList.add(id)
                    }
                }
                ingredientAdapter.submitWithHeaders(
                    elements = ingredients,
                    ingredientsTitle = getString(R.string.ingredients),
                    stepsTitle = "",
                    shoppingState = ingredientsOnShoppingList)
                stepAdapter.submitWithHeaders(
                    elements = steps,
                    ingredientsTitle = "",
                    stepsTitle = getString(R.string.steps),
                    shoppingState = emptySet()
                )
                val attachments = elements.filter {
                    it.instructionType == "link"
                            || it.instructionType == "pdf"
                            || (it.instructionType == "image" && it.attachmentType == "pdf")
                }
                attachmentItems.clear()
                attachments.forEach { el ->
                    when (el.instructionType) {
                        "link" -> attachmentItems.add(el.primaryText)
                        "pdf" -> attachmentItems.add("PDF: " + (el.instructionText ?: ""))
                    }
                }
                attachmentsAdapter.notifyDataSetChanged()
                attachmentsListView.visibility = if (attachmentItems.isNotEmpty()) View.VISIBLE else View.GONE
                attachmentsSection.visibility = if (attachmentItems.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
    private fun updateIngredientListScaling() {
        ingredientAdapter.currentServings = currentServings
    }

    private suspend fun nextOrderingIndex(): Long {
        val db = AppDatabase.getInstance(this)
        val current = db.recipeElementDao().getElementsForRecipe(recipeId!!).first()
        return (current.maxOfOrNull { it.orderingIndex } ?: -1L) + 1L
    }

    private fun addIngredient(isEditing: RecipeElement?) {
        val ctx = this
        val view = layoutInflater.inflate(R.layout.dialog_add_ingredient, null)
        val qty = view.findViewById<EditText>(R.id.inputQuantity)
        val unit = view.findViewById<EditText>(R.id.inputUnit)
        val name = view.findViewById<EditText>(R.id.inputIngredient)

        // Prefill from structured fields (iOS-style)
        isEditing?.let {
            if (it.quantity != 0f) qty.setText(
                String.format("%.1f", it.quantity)
                    .let { s -> if (s.endsWith(".0")) s.dropLast(2) else s }
            )
            unit.setText(it.primaryText)
            name.setText(it.secondaryText ?: "")
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (isEditing == null) getString(R.string.add_ingredient) else getString(R.string.edit))
            .setView(view)
            .setPositiveButton(R.string.save) { d, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(ctx)

                    val quantity = qty.text.toString().trim().toFloatLenient(0f)
                    val unitStr = unit.text.toString().trim()
                    val nameStr = name.text.toString().trim()

                    if (isEditing != null) {
                        isEditing.type = "ingredients"
                        isEditing.instructionType = "text"
                        isEditing.quantity = quantity
                        isEditing.primaryText = unitStr
                        isEditing.secondaryText = nameStr
                        // keep existing numberOfCurrentPeople as is
                        db.recipeElementDao().updateElement(isEditing)
                        EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    } else {
                        val idx = nextOrderingIndex()
                        val el = RecipeElement(
                            uniqueId = UUID.randomUUID(),
                            belongsToRecipeId = recipeId!!,
                            type = "ingredients",
                            instructionType = "text",
                            primaryText = unitStr,          // unit
                            instructionText = null,         // not used for ingredients
                            orderingIndex = idx,
                            attachmentData = null,
                            attachmentType = null,
                            imageData = null,
                            quantity = quantity,
                            secondaryText = nameStr,
                            numberOfCurrentPeople = null     // or wire recipe.numberOfPersons if desired
                        )
                        db.recipeElementDao().insertElement(el)
                        EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Disable/enable the Save button based on numeric quantity + non-empty ingredient name
            dialog.attachIngredientValidation(
                positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                quantityField = qty,
                ingredientField = name
            )
        }

        dialog.show()
    }

    private fun addLink(isEditing: RecipeElement?) {
        val ctx = this
        val view = layoutInflater.inflate(R.layout.dialog_add_link, null)
        val url = view.findViewById<EditText>(R.id.inputUrl)
        val display = view.findViewById<EditText>(R.id.inputDescription)

        if (isEditing != null) {
            url.setText(isEditing.primaryText)
            display.setText(isEditing.instructionText) // we’ll display instructionText as “display name”
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.add_weblink))
            .setView(view)
            .setPositiveButton(R.string.save) { d, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(ctx)
                    val normalized = url.text.toString().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
                    }
                    if (isEditing != null) {
                        isEditing.instructionType = "link"
                        isEditing.primaryText = normalized
                        isEditing.instructionText = display.text.toString()
                        // keep defaults for new fields as-is
                        db.recipeElementDao().updateElement(isEditing)
                        EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    } else {
                        val idx = nextOrderingIndex()
                        val el = RecipeElement(
                            uniqueId = UUID.randomUUID(),
                            belongsToRecipeId = recipeId!!,
                            type = "steps",
                            instructionType = "link",
                            primaryText = normalized,
                            instructionText = display.text.toString(),
                            orderingIndex = idx,
                            attachmentData = null,
                            attachmentType = null,
                            imageData = null,
                            quantity = 0f,
                            secondaryText = null,
                            numberOfCurrentPeople = null
                        )
                        db.recipeElementDao().insertElement(el)
                        EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handlePickedImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@RecipeDetailActivity)
            val startIdx = nextOrderingIndex()
            uris.forEachIndexed { i, uri ->
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@forEachIndexed
                val elId = UUID.randomUUID()

                val el = RecipeElement(
                    uniqueId = elId,
                    belongsToRecipeId = recipeId!!,
                    type = "steps",
                    instructionType = "image",
                    primaryText = "",
                    instructionText = null,
                    orderingIndex = startIdx + i,
                    attachmentData = null,
                    attachmentType = null,
                    imageData = null,
                    quantity = 0f,
                    secondaryText = null,
                    numberOfCurrentPeople = null
                )
                db.recipeElementDao().insertElement(el)

                // Save as <elId>.jpg using the extension
                applicationContext.saveImageToFilesDir(bytes, elId.toString())
            }
            withContext(Dispatchers.Main) {
                EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
            }
        }
    }

    private fun showPeopleChooser() {
        val quick = (1..8).map { it.toString() } + getString(R.string.more)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.number_of_people))
            .setItems(quick.toTypedArray()) { d, which ->
                if (which in 0..7) {
                    currentPeople = (which + 1).toLong()

                } else {
                    // “More…” -> input dialog
                    val input = EditText(this).apply {
                        hint = getString(R.string.for_example_4)
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.number_of_people))
                        .setMessage(getString(R.string.for_how_many_people))
                        .setView(input)
                        .setPositiveButton(R.string.continue_) { dd, _ ->
                            val v = input.text?.toString()?.trim()?.toLongOrNull()
                            if (v != null && v > 0) {
                                currentPeople = v

                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                d.dismiss()
            }
            .show()
    }

    fun editIngredient(ingredient: RecipeElement) {
        addIngredient(isEditing = ingredient)
    }


    fun confirmDeleteIngredient(element: RecipeElement) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_element))
            .setMessage(getString(R.string.delete_element_confirm))
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch {
                    val db = AppDatabase.getInstance(this@RecipeDetailActivity)
                    // delete any local files tied to this element (image/video preview)
                    val id = element.uniqueId
                    File(filesDir, "$id.jpg").takeIf { it.exists() }?.delete()
                    element.attachmentType?.let { ext ->
                        File(filesDir, "$id.$ext").takeIf { it.exists() }?.delete()
                    }
                    db.recipeElementDao().deleteElement(element)
                    EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    if (element.type == "ingredients") {
                        val ingredientId = element.uniqueId
                        if (ingredientId != null) {
                            ingredientsOnShoppingList.remove(ingredientId)
                            ingredientAdapter.updateShoppingStateFor(ingredientId, false)
                        }
                        EventBus.post(AppEvent.DidUpdateShoppingList)
                    }
                }
                    d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openMedia(el: RecipeElement) {
        // 1) Resolve the file on disk
        val file = when (el.instructionType) {
            "video" -> el.attachmentType?.let { ext -> File(filesDir, "${el.uniqueId}.$ext") }
            "image" -> File(filesDir, "${el.uniqueId}.jpg")
            // If later you support tapping PDFs directly, uncomment:
            // "pdf", "importedPDF" -> File(filesDir, "${el.uniqueId}.pdf")
            else -> null
        }
        if (file == null || !file.exists()) return

        // 2) Build a FileProvider content Uri
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )

        // 3) Pick the most precise MIME we can
        val mime = when (el.instructionType) {
            "video" -> {
                // Try to infer from extension; fall back to wildcard
                val ext = el.attachmentType?.lowercase()
                val guess = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext ?: "")
                guess ?: "video/*"
            }
            "image" -> "image/jpeg"
            // For direct PDF opening (if you enable it later):
            // "pdf", "importedPDF" -> "application/pdf"
            else -> "application/octet-stream"
        }

        // 4) Launch viewer
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Some OEM viewers (older Samsung) require ClipData to propagate the grant:
            clipData = android.content.ClipData.newRawUri("content", uri)
        }

        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.no_app_to_open_file),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply { hint = getString(R.string.name_ellipsis) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { d, _ ->
                lifecycleScope.launch {
                    val name = input.text?.toString()?.trim().orEmpty()
                    val db = AppDatabase.getInstance(this@RecipeDetailActivity)
                    val r = db.recipeDao().getRecipeById(recipeId!!) ?: return@launch
                    if (name.isNotEmpty()) {
                        db.recipeDao().update(r.copy(primaryText = name))
                        loadRecipe(recipeId!!)
                        EventBus.post(AppEvent.DidUpdateRecipe(recipeId))
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
