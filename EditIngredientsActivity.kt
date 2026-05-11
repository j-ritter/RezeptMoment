package com.example.rezeptmoment.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import com.example.rezeptmoment.RecipeElementAdapter
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.Recipe
import com.example.rezeptmoment.data.RecipeElement
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import kotlin.math.abs

class EditIngredientsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: RecipeElementAdapter
    private lateinit var fab: FloatingActionButton
    private lateinit var emptyButton: Button
    private lateinit var headerText: TextView

    private lateinit var db: AppDatabase
    private var recipeId: UUID? = null
    private var recipe: Recipe? = null
    private val elements: MutableList<RecipeElement> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_ingredients)

        db = AppDatabase.getInstance(this)

        // ✅ Consistent extra name
        val idStr = intent.getStringExtra("recipeId")
        recipeId = idStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        toolbar = findViewById(R.id.toolbar)
        recycler = findViewById(R.id.recyclerIngredients)
        fab = findViewById(R.id.fabAddIngredient)
        emptyButton = findViewById(R.id.btnAddFirstIngredient)
        headerText = findViewById(R.id.tvHeaderPersons)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.edit_ingredients)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = RecipeElementAdapter(
            onLinkClicked = { /* not used here */ },
            onIngredientClicked = { element -> openEditIngredient(element) }, // tap = edit
            onEditClicked = { element -> openEditIngredient(element) },       // long-press menu → Edit
            onDeleteClicked = { element -> confirmDeleteIngredient(element) } // long-press menu → Delete
        )
        recycler.adapter = adapter

        // Drag & drop reorder
        val touchHelper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.bindingAdapterPosition
                // Disable drag for headers entirely
                return if (pos != RecyclerView.NO_POSITION && adapter.isHeaderPosition(pos)) {
                    makeMovementFlags(0, 0)
                } else {
                    // Explicit: allow UP/DOWN, no swipe
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                }
            }

            // Also prevent dropping over a header row
            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val to = target.bindingAdapterPosition
                return to != RecyclerView.NO_POSITION && !adapter.isHeaderPosition(to)
            }

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (adapter.isHeaderPosition(from) || adapter.isHeaderPosition(to)) return false

                // Map adapter rows (with headers) → content indices
                val fromContent = adapter.rowToElementIndex(from) ?: return false
                val toContent   = adapter.rowToElementIndex(to)   ?: return false

                // Reorder backing list and re-submit with headers
                java.util.Collections.swap(elements, fromContent, toContent)
                adapter.submitWithHeaders(
                    elements.toList(),
                    getString(R.string.ingredients),
                    getString(R.string.steps)
                )
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) { /* no-op */ }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                persistOrder()
            }
        })
        touchHelper.attachToRecyclerView(recycler)
    }

    private fun loadRecipeAndIngredients() {
        val id = recipeId ?: return
        lifecycleScope.launch {
            // 1) Load recipe
            val loaded = db.recipeDao().getRecipeById(id)
            if (loaded == null) { finish(); return@launch }
            recipe = loaded

            // Header: "Standard value: N people"
            val people: Long = loaded.numberOfPersons
            headerText.text = getString(R.string.standard_value_people, people.toInt())

            // 2) Load elements for this recipe
            val all = db.recipeElementDao().getElementsForRecipe(id).first()

            // 3) Only ingredients (sorted)
            val ingredients = all.filter { it.type == "ingredients" }.sortedBy { it.orderingIndex }

            // 4) Update list + UI
            elements.clear()
            elements.addAll(ingredients)
            adapter.submitWithHeaders(
                ingredients.toList(),
                getString(R.string.ingredients),
                getString(R.string.steps)
            )


            val isEmpty = ingredients.isEmpty()
            emptyButton.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
            fab.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun confirmDeleteIngredient(element: RecipeElement) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_element))
            .setMessage(null)
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch {
                    db.upcomingDao().deleteUpcomingForIngredientIds(listOf(element.uniqueId))
                    db.recipeElementDao().deleteElement(element)
                    loadRecipeAndIngredients()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persistOrder() {
        lifecycleScope.launch {
            elements.forEachIndexed { index, el ->
                if (el.orderingIndex != index.toLong()) {
                    el.orderingIndex = index.toLong()
                    db.recipeElementDao().updateElement(el)
                }
            }
        }
    }

    private fun openEditIngredient(element: RecipeElement?) {
        val ctx = this
        val view = layoutInflater.inflate(R.layout.dialog_add_ingredient, null)
        val qty = view.findViewById<EditText>(R.id.inputQuantity)
        val unit = view.findViewById<EditText>(R.id.inputUnit)
        val name = view.findViewById<EditText>(R.id.inputIngredient)

        // Prefill if editing
        if (element != null) {
            val q = element.quantity
            if (abs(q) > 0.0001f) {
                val s = String.format("%.1f", q).replace(',', '.')
                qty.setText(if (s.endsWith(".0")) s.dropLast(2) else s)
            }
            unit.setText(element.primaryText.orEmpty())
            name.setText(element.secondaryText.orEmpty())
        }

        val titleRes = if (element == null) R.string.add_ingredient else R.string.edit
        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.save) { d, _ ->
                lifecycleScope.launch {
                    val rid = recipeId ?: return@launch
                    val q = qty.text?.toString()?.replace(',', '.')?.toFloatOrNull() ?: 0f
                    val unitStr = unit.text?.toString()?.trim().orEmpty()
                    val nameStr = name.text?.toString()?.trim().orEmpty()

                    if (element != null) {
                        // UPDATE existing
                        element.quantity = q
                        element.primaryText = unitStr
                        element.secondaryText = nameStr
                        db.recipeElementDao().updateElement(element)
                    } else {
                        // INSERT new — pass ALL constructor args (fixes “No value passed for parameter 'numberOfCurrentPeople'”)
                        val nextIndex = nextOrderingIndexForIngredients(rid)
                        val people = recipe?.numberOfPersons ?: 1L
                        val el = RecipeElement(
                            uniqueId = UUID.randomUUID(),
                            belongsToRecipeId = rid,
                            type = "ingredients",
                            instructionType = "text",
                            primaryText = unitStr,
                            instructionText = null,
                            orderingIndex = nextIndex,
                            attachmentData = null,
                            attachmentType = null,
                            imageData = null,
                            quantity = q,
                            secondaryText = nameStr,
                            numberOfCurrentPeople = people
                        )
                        db.recipeElementDao().insertElement(el)
                    }

                    loadRecipeAndIngredients()
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun nextOrderingIndexForIngredients(recipeId: UUID): Long {
        val elements = db.recipeElementDao().getElementsForRecipe(recipeId).first()
        val ing = elements.filter { it.type == "ingredients" }
        return (ing.maxOfOrNull { it.orderingIndex } ?: -1L) + 1L
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
