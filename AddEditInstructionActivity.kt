package com.example.rezeptmoment

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.RecipeElement
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class AddEditInstructionActivity : AppCompatActivity() {

    private lateinit var primaryTextField: EditText
    private lateinit var instructionTextField: EditText

    private var recipeId: UUID? = null
    private var editingInstruction: RecipeElement? = null
    private var recipeElements: MutableList<RecipeElement> = mutableListOf()
    private lateinit var repository: RecipeRepository
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_instruction)

        // bind views
        primaryTextField = findViewById(R.id.editTextPrimaryText)
        instructionTextField = findViewById(R.id.editTextInstruction)

        // initialize repository with all DAOs
        val db = AppDatabase.getInstance(applicationContext)
        repository = RecipeRepository(
            db.recipeDao(),
            db.recipeCategoryDao(),
            db.recipeElementDao(),
            db.premiumDao(),
            db.upcomingDao()
        )

        // --- Receive intent extras (support both UUID extra and String extra for compatibility) ---
        recipeId = (intent.getSerializableExtra("RECIPE_ID") as? UUID)
            ?: intent.getStringExtra("RECIPE_ID")?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        editingInstruction = intent.getSerializableExtra("EDIT_INSTRUCTION") as? RecipeElement

        if (editingInstruction != null) {
            primaryTextField.setText(editingInstruction?.primaryText)
            instructionTextField.setText(editingInstruction?.instructionText ?: "")
        }

        // Enable save button only if input not blank
        val watcher = { invalidateOptionsMenu() }
        primaryTextField.doAfterTextChanged { watcher() }
        instructionTextField.doAfterTextChanged { watcher() }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
        loadItems()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_add_edit_instruction, menu)
        val hasContent =
            !primaryTextField.text.isNullOrBlank() || !instructionTextField.text.isNullOrBlank()
        val saveItem = menu?.findItem(R.id.action_save)
        saveItem?.isEnabled = hasContent && !isLoading
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save -> {
                if (!isLoading) {
                    saveInstruction()
                } else {
                    Toast.makeText(this, "Please wait, data is still loading", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveInstruction() {
        val primary = primaryTextField.text.toString().trim()
        val instruction = instructionTextField.text.toString().trimEnd('\n')

        // nothing to save
        if (primary.isBlank() && instruction.isBlank()) {
            finish()
            return
        }

        if (editingInstruction != null) {
            // Update existing element (keep its orderingIndex as-is)
            val updated = editingInstruction!!.copy(
                primaryText = primary,
                instructionText = instruction
            )
            lifecycleScope.launch {
                repository.updateElement(updated)
                recipeId?.let { EventBus.post(AppEvent.DidUpdateRecipe(it)) }
                setResult(RESULT_OK)
                finish()
            }
        } else if (recipeId != null) {
            // Create new element with a proper Long orderingIndex
            lifecycleScope.launch {
                val nextOrder = getNextStepsOrderingIndex() // Long
                val newInstruction = RecipeElement(
                    uniqueId = UUID.randomUUID(),
                    belongsToRecipeId = recipeId!!,
                    type = "steps",
                    instructionType = "text",
                    primaryText = primary,
                    instructionText = instruction,
                    orderingIndex = nextOrder,   // Long ✔
                    attachmentData = null,
                    attachmentType = null,
                    imageData = null,
                    // required fields on entity:
                    quantity = 0f,
                    secondaryText = null,
                    numberOfCurrentPeople = 1L   // or recipe-based value if appropriate
                )
                repository.insertElement(newInstruction)
                EventBus.post(AppEvent.DidUpdateRecipe(recipeId!!))
                setResult(RESULT_OK)
                finish()
            }
        } else {
            finish()
        }
    }

    private suspend fun getNextStepsOrderingIndex(): Long {
        val id = recipeId ?: return 0L
        val elements = repository.getElementsForRecipe(id).firstOrNull().orEmpty()
        val steps = elements.filter { it.belongsToRecipeId == id && it.type == "steps" }
        // parity with other places: start at 0
        val currentMax = steps.maxOfOrNull { it.orderingIndex } ?: -1L
        return currentMax + 1L
    }

    private fun loadItems() {
        isLoading = true
        invalidateOptionsMenu()
        lifecycleScope.launch {
            try {
                recipeElements = repository
                    .getElementsForRecipe(recipeId ?: UUID.randomUUID())
                    .firstOrNull()
                    ?.toMutableList()
                    ?: mutableListOf()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AddEditInstructionActivity,
                    "Failed to load data",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isLoading = false
                invalidateOptionsMenu()
            }
        }
    }
}
