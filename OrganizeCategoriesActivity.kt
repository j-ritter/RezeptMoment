package com.example.rezeptmoment.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.Recipe
import com.example.rezeptmoment.data.RecipeCategory
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class OrganizeCategoriesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: CategoriesAdapter
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organize_categories)

        db = AppDatabase.getInstance(this)

        toolbar = findViewById(R.id.toolbar)
        recycler = findViewById(R.id.recyclerCategories)
        fab = findViewById(R.id.fabAddCategory)

        // Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // iOS uses "Edit Categories"
        supportActionBar?.title = getString(R.string.edit_categories)

        // RecyclerView + adapter
        adapter = CategoriesAdapter(
            onMenuClicked = { category, anchor -> showRowMenu(category, anchor) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // Drag & drop with long-press anywhere
        val touchHelper = ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0 /* swipe disabled */
            ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                // no-op
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // Commit new order to DB
                persistOrder()
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(recycler)

        // Add new category
        fab.setOnClickListener { showCreateDialog() }

        // Observe categories + recipes together so we can show "N recipes"
        lifecycleScope.launch {
            val daoCat = db.recipeCategoryDao()
            val daoRcp = db.recipeDao()

            combine(
                daoCat.getAllCategories(),   // Flow<List<RecipeCategory>>
                daoRcp.getAllRecipes()       // Flow<List<Recipe>>
            ) { categories, recipes ->
                // Build counts per category (ignore recipes without a category)
                val counts: Map<UUID, Int> = recipes
                    .filter { it.belongsToCategoryId != null }
                    .groupBy { it.belongsToCategoryId!! }
                    .mapValues { it.value.size }

                Pair(categories, counts)
            }.collect { (categories, counts) ->
                // DAO already ORDER BY orderingIndex ASC; if not, sort here
                adapter.setRecipeCounts(
                    categories.associate { cat ->
                        cat.uniqueId to recipesCountFor(cat.uniqueId, counts)
                    }
                )
                adapter.submitList(categories)
            }
        }
    }

    // ---- helpers & actions (must be OUTSIDE onCreate) ----

    private fun recipesCountFor(catId: UUID, counts: Map<UUID, Int>): Int {
        return counts[catId] ?: 0
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showRowMenu(category: RecipeCategory, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_category_row, popup.menu)
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                R.id.action_rename -> {
                    showRenameDialog(category)
                    true
                }
                R.id.action_delete -> {
                    confirmDelete(category)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_category, null)
        val input = view.findViewById<TextInputEditText>(R.id.inputCategoryName)
        val currentUserEmail = getSharedPreferences("RezeptmomentPrefs", MODE_PRIVATE)
            .getString("USER_EMAIL", "") ?: ""

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_category))
            .setView(view)
            .setPositiveButton(R.string.create) { d, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val current = adapter.items // exposed list
                        val nextOrder = (current.maxOfOrNull { it.orderingIndex } ?: -1L) + 1L
                        val cat = RecipeCategory(
                            uniqueId = UUID.randomUUID(),
                            primaryText = name,
                            orderingIndex = nextOrder,
                            userId = currentUserEmail
                        )
                        db.recipeCategoryDao().insertCategory(cat)
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(category: RecipeCategory) {
        val view = layoutInflater.inflate(R.layout.dialog_new_category, null)
        val input = view.findViewById<TextInputEditText>(R.id.inputCategoryName)
        input.setText(category.primaryText)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_category))
            .setView(view)
            .setPositiveButton(R.string.save) { d, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty() && name != category.primaryText) {
                    lifecycleScope.launch {
                        db.recipeCategoryDao().updateCategory(
                            category.copy(primaryText = name)
                        )
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(category: RecipeCategory) {
        lifecycleScope.launch {
            val recipes = db.recipeDao().getAllRecipes().first()
            val count = recipes.count { it.belongsToCategoryId == category.uniqueId }

            if (count > 0) {
                // iOS-style warning (no delete allowed if not empty)
                AlertDialog.Builder(this@OrganizeCategoriesActivity)
                    .setTitle(getString(R.string.this_section_contains_items))
                    .setMessage(getString(R.string.clear_section_before_deleting))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                // Safe to delete
                AlertDialog.Builder(this@OrganizeCategoriesActivity)
                    .setTitle(getString(R.string.delete_category))
                    .setMessage(getString(R.string.delete_category_confirm, category.primaryText))
                    .setPositiveButton(R.string.delete) { d, _ ->
                        lifecycleScope.launch {
                            db.recipeCategoryDao().deleteCategory(category)
                        }
                        d.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun persistOrder() {
        lifecycleScope.launch {
            val reordered = adapter.items.mapIndexed { index, cat ->
                cat.copy(orderingIndex = index.toLong())
            }
            db.recipeCategoryDao().updateCategories(reordered)
        }
    }
}
