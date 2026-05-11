package com.example.rezeptmoment

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.AttachmentType
import com.example.rezeptmoment.data.AttachmentUi
import com.example.rezeptmoment.data.EditableIngredient
import com.example.rezeptmoment.data.Recipe
import com.example.rezeptmoment.data.RecipeCategory
import com.example.rezeptmoment.RecipeRepository
import com.example.rezeptmoment.ui.CategoryNamingDialog
import com.example.rezeptmoment.ui.util.saveImageToFilesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.*

class CreateRecipeActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    private lateinit var previewImage: ImageView
    private var selectedImageBytes: ByteArray? = null
    private var editingRecipeId: UUID? = null
    private var shouldTriggerPopUp: Boolean = false
    private val ingredientList = mutableListOf<EditableIngredient>()
    private val instructionList = mutableListOf<String>()
    private val attachmentList = mutableListOf<AttachmentUi>()
    private lateinit var attachmentsAdapter: ArrayAdapter<String>
    private lateinit var ingredientsAdapter: IngredientAdapter
    private lateinit var instructionsAdapter: ArrayAdapter<String>
    private val REQUEST_SCAN_PAGE = 201
    private var currentPeople: Long = 1
    private lateinit var repository: RecipeRepository
    private lateinit var viewModel: RecipesViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("CreateRecipeActivity", "onCreate called, CATEGORY_ID=${intent.getStringExtra("CATEGORY_ID")}")
        setContentView(R.layout.activity_create_recipe)
        currentPeople = 2  // default
        findViewById<TextView>(R.id.servingsCount).text = currentPeople.toString()

        titleInput = findViewById(R.id.inputTitle)
        categorySpinner = findViewById(R.id.spinnerCategory)
        saveButton = findViewById(R.id.buttonSave)
        cancelButton = findViewById(R.id.buttonCancel)
        previewImage = findViewById(R.id.imagePreview)

        titleInput.setOnClickListener { showRecipeTitleDialog() }
        previewImage.setOnClickListener { openGallery() }
        saveButton.setOnClickListener { saveRecipe() }
        cancelButton.setOnClickListener { finish() }

        val ingredientsListView = findViewById<ListView>(R.id.ingredientsListView)
        val instructionsListView = findViewById<ListView>(R.id.instructionsListView)
        val attachmentsListView = findViewById<ListView>(R.id.attachmentsListView)

        attachmentsAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            attachmentList.map { it.label }
        )
        attachmentsListView.adapter = attachmentsAdapter

        ingredientsAdapter = IngredientAdapter(
            ingredientList,
            { currentPeople },
            onIngredientClick = { position ->
                showEditIngredientDialog(position)
            }
        )
        ingredientsListView.adapter = ingredientsAdapter


        instructionsAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            instructionList
        )
        instructionsListView.adapter = instructionsAdapter


        // Contextual menu for add ingredient (new logic)
        val addIngredientBtn = findViewById<ImageView>(R.id.addIngredient)
        addIngredientBtn.setOnClickListener {
            showAddIngredientTextDialog()
        }

        val servingsMinus = findViewById<ImageButton>(R.id.servingsMinus)
        val servingsPlus = findViewById<ImageButton>(R.id.servingsPlus)
        val servingsCount = findViewById<TextView>(R.id.servingsCount)

        servingsMinus.setOnClickListener {
            if (currentPeople > 1) {
                currentPeople--
                servingsCount.text = currentPeople.toString()
                ingredientsAdapter.notifyDataSetChanged()
            }
        }

        servingsPlus.setOnClickListener {
            currentPeople++
            servingsCount.text = currentPeople.toString()
            ingredientsAdapter.notifyDataSetChanged()
        }
        // Contextual menu for add instruction (match ingredient pattern if needed)
        val addInstructionBtn = findViewById<ImageView>(R.id.addInstruction)
        addInstructionBtn.setOnClickListener { showAddInstructionDialog() }

        val addAttachmentBtn = findViewById<ImageView>(R.id.addAttachment)
        addAttachmentBtn.setOnClickListener {
            showAddAttachmentDialog()
        }

        val recipeIdStr = intent.getStringExtra("recipeId")
        editingRecipeId = recipeIdStr?.let { UUID.fromString(it) }
        shouldTriggerPopUp = intent.getBooleanExtra("shouldTriggerPopUp", false)

        // Initialize the repository with your database DAOs
        val db = AppDatabase.getInstance(this)
        repository = RecipeRepository(
            db.recipeDao(),
            db.recipeCategoryDao(),
            db.recipeElementDao(),
            db.premiumDao(),
            db.upcomingDao()
        )

        viewModel = RecipesViewModel(repository)

        // Load categories reactively
        val prefs = getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)
        val email = prefs.getString("USER_EMAIL", "")?.lowercase()?.trim() ?: ""

        if (email.isNotBlank()) {
            viewModel.loadCategoriesForUser(email)
        } else {
            // Redirect to login or disable save button
            saveButton.isEnabled = false
            Toast.makeText(this, "Please login first", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.loadCategoriesForUser(email)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.categories.collect { categories ->
                    // ALWAYScreate the adapter, even if categories is empty
                    val adapter = ArrayAdapter(
                        this@CreateRecipeActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        categories.map { it.primaryText })
                    categorySpinner.adapter = adapter

                    // Only try to set selection if we actually have dataif (categories.isNotEmpty()) {
                    val prefillCategoryId = intent.getStringExtra("CATEGORY_ID")
                    val idx = categories.indexOfFirst { it.uniqueId.toString() == prefillCategoryId }

                    // Defaultto 0 (which will be "My Recipe" if it's the only one)
                    categorySpinner.setSelection(if (idx != -1) idx else 0)
                }
            }
        }
    }


    private fun showRecipeTitleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_recipe, null)
        val editText = dialogView.findViewById<EditText>(R.id.titleEditText)
        editText.setText(titleInput.text)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<Button>(R.id.dialogCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<Button>(R.id.dialogSave).setOnClickListener {
            titleInput.setText(editText.text.toString())
            dialog.dismiss()
        }
        dialog.show()
    }



    fun onCategoryDialogDismissed() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    val inputStream = contentResolver.openInputStream(it)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    selectedImageBytes = baos.toByteArray()
                    previewImage.setImageBitmap(bitmap)
                }
            }
        }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun showAddIngredientTextDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_ingredient, null)
        val inputQuantity = dialogView.findViewById<EditText>(R.id.inputQuantity)
        val inputUnit = dialogView.findViewById<EditText>(R.id.inputUnit)
        val inputIngredient = dialogView.findViewById<EditText>(R.id.inputIngredient)

        AlertDialog.Builder(this)
            .setTitle("Add Ingredient")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val quantityStr = inputQuantity.text.toString().trim()
                val unit = inputUnit.text.toString().trim()
                val ingredientName = inputIngredient.text.toString().trim()

                // Validate name
                if (ingredientName.isEmpty()) {
                    Toast.makeText(this, "Ingredient name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Validate quantity, if specified
                val quantity = quantityStr.toFloatOrNull()
                if (quantityStr.isNotEmpty() && (quantity == null || quantity <= 0f)) {
                    Toast.makeText(this, "Quantity must be a positive number", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                // Add ingredient if count < 30
                if (ingredientList.size < 30) {
                    ingredientList.add(
                        EditableIngredient(
                            originalQuantity = quantity ?: 0f,
                            unit = unit,
                            name = ingredientName,
                            baseServings = currentPeople
                        )
                    )
                    ingredientsAdapter.notifyDataSetChanged()
                    findViewById<ListView>(R.id.ingredientsListView).visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Maximum 30 ingredients", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

        private fun showTakePhotoOrVideoDialog() {
        val options = arrayOf("Take Photo", "Take Video")
        AlertDialog.Builder(this)
            .setTitle("Add Photo or Video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCameraIntent(photo = true)
                    1 -> launchCameraIntent(photo = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val REQUEST_IMAGE_CAPTURE = 101
    private val REQUEST_VIDEO_CAPTURE = 102

    private fun launchCameraIntent(photo: Boolean) {
        val intent = if (photo) {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        } else {
            Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        }
        if (intent.resolveActivity(packageManager) != null) {
            val requestCode = if (photo) REQUEST_IMAGE_CAPTURE else REQUEST_VIDEO_CAPTURE
            startActivityForResult(intent, requestCode)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditIngredientDialog(position: Int) {
        val ingredient = ingredientList[position]
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_ingredient, null)
        val inputQuantity = dialogView.findViewById<EditText>(R.id.inputQuantity)
        val inputUnit = dialogView.findViewById<EditText>(R.id.inputUnit)
        val inputIngredient = dialogView.findViewById<EditText>(R.id.inputIngredient)

        // Pre-fill fields
        inputQuantity.setText(
            if (ingredient.originalQuantity != 0f) ingredient.originalQuantity.trimZero() else ""
        )
        inputUnit.setText(ingredient.unit)
        inputIngredient.setText(ingredient.name)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Ingredient")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Update", null) // We'll override this to prevent auto-dismiss
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val quantityStr = inputQuantity.text.toString().trim()
                val unit = inputUnit.text.toString().trim()
                val name = inputIngredient.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(this, "Ingredient name is required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val quantity = quantityStr.toFloatOrNull()
                if (quantityStr.isNotEmpty() && (quantity == null || quantity <= 0f)) {
                    Toast.makeText(this, "Quantity must be a positive number", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Update object
                ingredient.originalQuantity = quantity ?: 0f

                ingredient.name = name
                ingredient.unit = unit
                ingredient.baseServings = currentPeople // optionally update base serving if you want

                ingredientsAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun Float.trimZero(): String = if (this % 1.0f == 0.0f) this.toInt().toString() else this.toString()


    private fun showAddInstructionDialog() {
        val input = EditText(this)
        input.hint = "Write instruction..."
        AlertDialog.Builder(this)
            .setTitle("Add Instruction")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val instruction = input.text.toString().trim()
                if (instruction.isNotEmpty()) {
                    if (instructionList.size < 30) {
                        instructionList.add(instruction)
                        instructionsAdapter.notifyDataSetChanged()
                        // MAKE VISIBLE IF HIDDEN:
                        findViewById<ListView>(R.id.instructionsListView).visibility = View.VISIBLE
                    } else {
                        Toast.makeText(this, "Maximum 30 instructions", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showAddAttachmentDialog() {
        val options = arrayOf("Add Link", "Scan Pages", "Import PDF")
        AlertDialog.Builder(this)
            .setTitle("Add Attachment")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddLinkDialog()
                    1 -> launchScanPages()
                    2 -> launchImportPdf()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddLinkDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_link, null)
        val inputUrl = dialogView.findViewById<EditText>(R.id.inputUrl)
        val inputDesc = dialogView.findViewById<EditText>(R.id.inputDescription)

        AlertDialog.Builder(this)
            .setTitle("Add Web Link")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val urlInput = inputUrl.text.toString().trim()
                val description = inputDesc.text.toString().trim()

                // --- Step 1: Basic empty check ---
                if (urlInput.isEmpty()) {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // --- Step 2: Normalize user input ---
                // Automatically adds https:// if missing
                val normalizedUrl = when {
                    urlInput.startsWith("http://", true) || urlInput.startsWith("https://", true) -> urlInput
                    else -> "https://$urlInput"
                }

                // --- Step 3: Validate the final normalized URL ---
                if (!android.util.Patterns.WEB_URL.matcher(normalizedUrl).matches()) {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // --- Step 4: Display entry in list ---
                val label = if (description.isNotEmpty()) "$description - $normalizedUrl" else normalizedUrl
                attachmentList.add(AttachmentUi(AttachmentType.LINK, label, normalizedUrl))
                refreshAttachmentsList()

                // (Optional) --- Step 5: Inform user ---
                Toast.makeText(this, "Link added successfully!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun launchScanPages() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, REQUEST_SCAN_PAGE)
        } else {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }
    private val REQUEST_IMPORT_PDF = 301

    private fun launchImportPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/pdf"
        startActivityForResult(intent, REQUEST_IMPORT_PDF)
    }

    private fun saveRecipe() {
        Log.d("RecipeDebug", "saveRecipe invoked")

        lifecycleScope.launch(Dispatchers.IO) {
            val currentUserEmail = getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)
                .getString("USER_EMAIL", "") ?: ""
            val title = titleInput.text.toString().trim()
            if (title.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CreateRecipeActivity, "Please enter a title", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            // Get categories from ViewModel directly (no more Spinner.tag)
            val categories = viewModel.categories.value
            val selectedIndex = withContext(Dispatchers.Main) { categorySpinner.selectedItemPosition }
            val selectedCategory = categories.getOrNull(selectedIndex)

            if (selectedCategory == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CreateRecipeActivity, "Data not ready, please wait.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val peopleCount = findViewById<TextView>(R.id.servingsCount).text.toString().toLongOrNull() ?: 1L
            val db = AppDatabase.getInstance(this@CreateRecipeActivity)

            Log.d("RecipeDebug", "Selected category: $selectedCategory")

            if (selectedCategory == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CreateRecipeActivity, "Please select a category before saving.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val id = editingRecipeId ?: UUID.randomUUID()

            // Save image if available
            selectedImageBytes?.let { bytes ->
                val saved = applicationContext.saveImageToFilesDir(bytes, id.toString())
                if (saved == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CreateRecipeActivity, "Failed to save image file", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            Log.d("RecipeDebug", "Preparing to create recipe. editId=$editingRecipeId, title=$title, selectedCategory=$selectedCategory")

            val recipe = if (editingRecipeId != null) {
                val existing = db.recipeDao().getRecipeById(id)
                Log.d("RecipeDebug", "edit mode: fetched existing=$existing")
                if (existing != null) {
                    existing.copy(
                        uniqueId = id,
                        primaryText = title,
                        belongsToCategoryId = selectedCategory.uniqueId,
                        image = existing.image?.takeIf { selectedImageBytes == null }?.let { null },
                        numberOfPersons = peopleCount,
                        userId = currentUserEmail
                    )
                } else {
                    Log.e("RecipeDebug", "edit mode: recipe not found, creating new object.")
                    Recipe(
                        uniqueId = id,
                        primaryText = title,
                        belongsToCategoryId = selectedCategory.uniqueId,
                        orderingIndex = System.currentTimeMillis(),
                        isMarked = false,
                        dateOfMarking = null,
                        image = null,
                        numberOfPersons = peopleCount,
                        userId = currentUserEmail
                    )
                }
            } else {
                Recipe(
                    uniqueId = id,
                    primaryText = title,
                    belongsToCategoryId = selectedCategory.uniqueId,
                    orderingIndex = System.currentTimeMillis(),
                    isMarked = false,
                    dateOfMarking = null,
                    image = null,
                    numberOfPersons = peopleCount,
                    userId = currentUserEmail
                )
            }

            try {
                db.recipeDao().insertRecipe(recipe)
                Log.d("RecipeDebug", "Recipe inserted successfully: ${recipe.uniqueId}")

                // === DELETE OLD ELEMENTS & SAVE NEW ONES ===
                db.recipeElementDao().deleteElementsForRecipe(id)

                for (ingredient in ingredientList) {
                    ingredient.originalQuantity = ingredient.getScaledQuantity(currentPeople)
                    ingredient.baseServings = currentPeople
                }

                var index = 0
                // Save ingredients
                for (ingredient in ingredientList) {
                    val element = com.example.rezeptmoment.data.RecipeElement(
                        uniqueId = UUID.randomUUID(),
                        belongsToRecipeId = id,
                        type = "ingredients",
                        instructionType = "text",
                        primaryText = ingredient.name,
                        instructionText = null,
                        orderingIndex = index.toLong(),
                        attachmentData = null,
                        attachmentType = null,
                        imageData = null,
                        quantity = ingredient.originalQuantity,        // FIX: Save actual quantity
                        secondaryText = ingredient.unit,
                        numberOfCurrentPeople = ingredient.baseServings
                    )
                    db.recipeElementDao().insertElement(element)
                    index++
                }

                // Save instructions
                index = 0
                for (step in instructionList) {
                    val element = com.example.rezeptmoment.data.RecipeElement(
                        uniqueId = UUID.randomUUID(),
                        belongsToRecipeId = id,
                        type = "steps",
                        instructionType = "text",
                        primaryText = "",
                        instructionText = step,
                        orderingIndex = index.toLong(),
                        attachmentData = null,
                        attachmentType = null,
                        imageData = null,
                        quantity = 0f,
                        secondaryText = null,
                        numberOfCurrentPeople = null
                    )
                    db.recipeElementDao().insertElement(element)
                    index++
                }

                // === SAVE ATTACHMENTS ===
                index = 0
                for (attachment in attachmentList) {
                    val instructionType = when (attachment.type) {
                        AttachmentType.LINK -> "link"
                        AttachmentType.PDF -> "pdf"
                        AttachmentType.SCAN_PAGE -> "scan"
                    }
                    val element = com.example.rezeptmoment.data.RecipeElement(
                        uniqueId = UUID.randomUUID(),
                        belongsToRecipeId = id,
                        type = "attachment",
                        instructionType = instructionType,
                        primaryText = attachment.data,      // This is the link URL, PDF URI, or image path
                        instructionText = attachment.label, // This is the display label
                        orderingIndex = index.toLong(),
                        attachmentData = null,
                        attachmentType = if (attachment.type == AttachmentType.PDF) "pdf" else null,
                        imageData = null,
                        quantity = 0f,
                        secondaryText = null,
                        numberOfCurrentPeople = null
                    )
                    db.recipeElementDao().insertElement(element)
                    index++
                }


                Log.d("RecipeDebug", "Inserted ${ingredientList.size} ingredients, ${instructionList.size} steps, and ${attachmentList.size} attachments for recipe $id")

            } catch (e: Exception) {
                Log.e("RecipeDebug", "Error inserting recipe or elements: ${e.message}", e)
            }

            // === CATEGORY POP-UP LOGIC ===
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val dontShowAgain = prefs.getBoolean("dont_show_category_dialog", false)
            val isDefaultCategory = selectedCategory.primaryText == getString(R.string.default_category_name)

            withContext(Dispatchers.Main) {
                if (isDefaultCategory && !dontShowAgain) {
                    showCategorySuggestionDialog()
                } else {
                    Toast.makeText(this@CreateRecipeActivity, "Recipe created!", Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun showCategorySuggestionDialog() {
        val checkBox = CheckBox(this)
        checkBox.text = "Don't show again"

        AlertDialog.Builder(this)
            .setTitle("Name your categories")
            .setMessage("Would you like to organize your recipes by naming categories now?")
            .setView(checkBox)
            .setNegativeButton("Later") { _, _ ->
                if (checkBox.isChecked) {
                    getSharedPreferences("prefs", MODE_PRIVATE)
                        .edit().putBoolean("dont_show_category_dialog", true).apply()
                }
                setResult(Activity.RESULT_OK)
                finish()
            }
            .setPositiveButton("Yes") { _, _ ->
                if (checkBox.isChecked) {
                    getSharedPreferences("prefs", MODE_PRIVATE)
                        .edit().putBoolean("dont_show_category_dialog", true).apply()
                }
                val dialog = CategoryNamingDialog()
                dialog.show(supportFragmentManager, "CategoryNamingDialog")
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_SCAN_PAGE -> {
                val bitmap = data?.extras?.get("data") as? Bitmap
                if (bitmap != null) {
                    // Optionally save bitmap to file and use path in `.data`
                    val label = "Scanned Page - ${System.currentTimeMillis()}"
                    // You could use an actual file path that you saved the image to, here we just pass empty
                    attachmentList.add(AttachmentUi(AttachmentType.SCAN_PAGE, label, ""))
                    refreshAttachmentsList() // <-- call here
                } else {
                    Toast.makeText(this, "Failed to scan page", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_IMPORT_PDF -> {
                val pdfUri = data?.data
                if (pdfUri != null) {
                    contentResolver.takePersistableUriPermission(
                        pdfUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val label = "PDF: ${pdfUri.lastPathSegment ?: pdfUri.toString()}"
                    attachmentList.add(AttachmentUi(AttachmentType.PDF, label, pdfUri.toString()))
                    refreshAttachmentsList() // <-- call here
                } else {
                    Toast.makeText(this, "No PDF selected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshAttachmentsList() {
        attachmentsAdapter.clear()
        attachmentsAdapter.addAll(attachmentList.map { it.label })
        attachmentsAdapter.notifyDataSetChanged()
        findViewById<ListView>(R.id.attachmentsListView).visibility =
            if (attachmentList.isNotEmpty()) View.VISIBLE else View.GONE
    }

}
