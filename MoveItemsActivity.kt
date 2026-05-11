package com.example.rezeptmoment.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/* ---- shared row model (header + item) ---- */
sealed class MoveRow {
    data class Header(val category: RecipeCategory) : MoveRow()
    data class Item(val recipe: Recipe) : MoveRow()
}

class MoveItemsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: MoveItemsAdapter
    private lateinit var db: AppDatabase

    private var categories: List<RecipeCategory> = emptyList()
    private var recipes: List<Recipe> = emptyList()
    private val rows = mutableListOf<MoveRow>()

    // Optional: scroll to this recipe once
    private var shouldScrollToId: UUID? = null
    private var didScrollOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_move_items)

        db = AppDatabase.getInstance(this)
        toolbar = findViewById(R.id.toolbarMove)
        recycler = findViewById(R.id.recyclerMove)

        shouldScrollToId = intent.getStringExtra("RECIPE_ID")?.let { UUID.fromString(it) }

        // Toolbar (iOS: configureNavbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.move_recipes)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Recycler + adapter
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        adapter = MoveItemsAdapter(
            onClickRecipe = { /* iOS just deselects; no-op */ }
        )
        recycler.adapter = adapter

        // Drag & drop across categories (iOS: dataSource.reorderingHandlers)
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos = to.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                // Disallow dragging a header OR dropping onto a header
                if (rows[fromPos] is MoveRow.Header || rows[toPos] is MoveRow.Header) return false

                // Move the item in memory
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // Highlight the row being dragged
                    adapter.setDragPosition(viewHolder.bindingAdapterPosition)
                } else {
                    // Clear highlight when drag ends or not dragging
                    adapter.setDragPosition(null)
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // Haptic feedback
                viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                // Persist new order + category mapping
                persistOrderToDb()
                // Snackbar after drag-drop
                Snackbar.make(rv, rv.context.getString(R.string.recipe_moved), Snackbar.LENGTH_SHORT).show()
            }

        })
        touchHelper.attachToRecyclerView(recycler)

        // Load and build the list
        loadItems()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Load categories + recipes, rebuild rows (iOS: loadItems + reloadDataSource) */
    private fun loadItems() {
        lifecycleScope.launch {
            categories = db.recipeCategoryDao().getAllCategories().first()
                .sortedBy { it.orderingIndex }

            recipes = db.recipeDao().getAllRecipes().first()

            // ---- A) rebuild rows ----
            val builtRows = rebuildRows(categories, recipes)

            // ---- B) update adapter (your requested lines) ----
            rows.clear()
            rows.addAll(builtRows)
            adapter.submitRows(rows)

            // ---- C) scroll if needed (once) ----
            scrollToItemIfNeeded()
        }
    }

    /** Build the flattened list of rows (headers + items) */
    private fun rebuildRows(
        categories: List<RecipeCategory>,
        recipes: List<Recipe>
    ): List<MoveRow> {
        val result = mutableListOf<MoveRow>()
        categories.forEach { cat ->
            result.add(MoveRow.Header(cat))
            val itemsInCat = recipes
                .filter { it.belongsToCategoryId == cat.uniqueId }
                .sortedBy { it.orderingIndex }
            itemsInCat.forEach { r -> result.add(MoveRow.Item(r)) }
        }
        return result
    }

    /** Scroll to a specific recipe once (iOS: scrollToItem) */
    private fun scrollToItemIfNeeded() {
        if (didScrollOnce) return
        val targetId = shouldScrollToId ?: return
        val index = rows.indexOfFirst { r ->
            r is MoveRow.Item && r.recipe.uniqueId == targetId
        }
        if (index >= 0) {
            recycler.post { recycler.scrollToPosition(index) }
            didScrollOnce = true
        }
    }

    /** Persist order + section after a drag (iOS: didReorder + saveItems) */
    private fun persistOrderToDb() {
        lifecycleScope.launch {
            val updates = mutableListOf<Recipe>()
            var currentCategoryId: UUID? = null
            var indexInSection = 0L

            rows.forEach { row ->
                when (row) {
                    is MoveRow.Header -> {
                        currentCategoryId = row.category.uniqueId
                        indexInSection = 0L
                    }
                    is MoveRow.Item -> {
                        val r = row.recipe
                        val newRecipe = r.copy(
                            belongsToCategoryId = currentCategoryId,
                            orderingIndex = indexInSection
                        )
                        updates.add(newRecipe)
                        indexInSection += 1
                    }
                }
            }

            // Apply updates
            updates.forEach { db.recipeDao().update(it) }
            // Refresh backing lists (keep UI in sync)
            loadItems()
        }
    }

    /* ---------------- Adapter (kept inline) ---------------- */
    private class MoveItemsAdapter(
        private val onClickRecipe: (Recipe) -> Unit,
        private var dragPosition: Int? = null

    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = mutableListOf<MoveRow>()

        fun submitRows(newRows: List<MoveRow>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        fun moveItem(from: Int, to: Int) {
            if (from == to) return
            val item = rows.removeAt(from)
            rows.add(to, item)
            notifyItemMoved(from, to)
        }

        fun setDragPosition(position: Int?) {
            dragPosition = position
            notifyItemChanged(position ?: return)
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is MoveRow.Header -> VIEW_HEADER
            is MoveRow.Item   -> VIEW_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = android.view.LayoutInflater.from(parent.context)
            val v = inflater.inflate(R.layout.item_simple_row, parent, false)
            return if (viewType == VIEW_HEADER) HeaderVH(v) else ItemVH(v)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            val isDragging = position == dragPosition
            when {
                holder is HeaderVH && row is MoveRow.Header -> holder.bind(row)
                holder is ItemVH && row is MoveRow.Item     -> holder.bind(row, onClickRecipe, isDragging)
            }
        }

        override fun getItemCount(): Int = rows.size

        /* Header row: show title, hide chevron */
        private class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.title)
            private val chevron: ImageView = itemView.findViewById(R.id.chevron)
            fun bind(row: MoveRow.Header) {
                title.text = row.category.primaryText
                chevron.isVisible = false
            }
        }

        /* Item row: show title, chevron visible */
        private class ItemVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.title)
            private val chevron: ImageView = itemView.findViewById(R.id.chevron)
            fun bind(row: MoveRow.Item, onClick: (Recipe) -> Unit, isDragging: Boolean) {
                title.text = row.recipe.primaryText ?: itemView.context.getString(R.string.untitled_recipe)
                chevron.isVisible = true
                itemView.setOnClickListener { onClick(row.recipe) }
                // Highlight on drag
                if (isDragging) {
                    itemView.setBackgroundColor(itemView.context.getColor(R.color.colorDragHighlight))
                } else {
                    itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
        }

        companion object {
            private const val VIEW_HEADER = 0
            private const val VIEW_ITEM = 1
        }
    }
}
