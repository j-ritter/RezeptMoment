package com.example.rezeptmoment

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.UpcomingObject
import com.example.rezeptmoment.ui.SectionHeaderAdapter
import com.example.rezeptmoment.ui.theme.SectionElement
import kotlinx.coroutines.launch
import java.util.UUID

class UpcomingFragment : Fragment(R.layout.fragment_upcoming) {

    private val viewModel: UpcomingViewModel by viewModels()
    private lateinit var adapter: UpcomingAdapter
    private val expandedSections = mutableSetOf<UUID>()


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Toolbar setup
        val toolbarMenu = view.findViewById<ImageView>(R.id.toolbar_menu_shopping)
        val toolbarExport = view.findViewById<ImageView>(R.id.toolbar_export_shopping)
        val toolbarAdd = view.findViewById<ImageView>(R.id.toolbar_add)


        // Menu button → open Drawer
        toolbarMenu.setOnClickListener {
            val drawerLayout = activity?.findViewById<DrawerLayout>(R.id.drawer_layout)
            drawerLayout?.open()
        }

        // Export button → export shopping list
        toolbarExport.setOnClickListener {
            exportShoppingList()
        }

        // Add button → open dialog for new item
        toolbarAdd.setOnClickListener {
            addNewShoppingItem()
        }

        // Recycler setup
        val recyclerView = view.findViewById<RecyclerView>(R.id.upcomingRecyclerView)
        val deleteButton = view.findViewById<Button>(R.id.deleteButtonShopping)
        val tutorialLabel = view.findViewById<TextView>(R.id.tutorialLabel)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Observe shopping list sections, build ConcatAdapter with expand/collapse
        viewModel.sections.observe(viewLifecycleOwner) { sections ->
            if (expandedSections.isEmpty()) {
                sections.forEach { (section, _) ->
                    expandedSections.add(section.id) // start all expanded
                }
            }
            updateShoppingSections(recyclerView, deleteButton, tutorialLabel, sections)
        }

        // Load items
        viewModel.loadItems()

        // Delete button → remove completed items
        deleteButton.setOnClickListener { viewModel.deleteCompleted() }
    }

    private fun addNewShoppingItem() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_add_shoppinglist, null)

        val qty = view.findViewById<EditText>(R.id.inputQuantityShopping)
        val unit = view.findViewById<EditText>(R.id.inputUnitShopping)
        val name = view.findViewById<EditText>(R.id.inputIngredientShopping)

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.add_shopping_item))
            .setView(view)
            .setPositiveButton(R.string.add) { dialog, _ ->
                val quantity = qty.text.toString().trim().toFloatOrNull() ?: 0f
                val unitStr = unit.text.toString().trim()
                val nameStr = name.text.toString().trim()
                val finalName = if (nameStr.isNotEmpty()) nameStr else getString(R.string.new_shopping_item)

                viewModel.addUpcomingObjectFromDialog(
                    quantity = quantity,
                    unit = unitStr,
                    name = finalName
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportShoppingList() {
        val items = viewModel.sections.value?.flatMap { (_, list) -> list } ?: emptyList()
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), "Shopping list is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val exportText = buildString {
            for (item in items) {
                val mark = if (item.isMarkedAsComplete) "✓  " else "◯  "
                val qty = if (item.quantity != 0f) {
                    if (item.quantity % 1 == 0f) item.quantity.toInt().toString() else item.quantity.toString()
                } else ""
                val unit = item.primaryText?.trim().orEmpty()
                val name = item.secondaryText?.trim().orEmpty()
                val line = listOf(qty, unit, name).filter { it.isNotEmpty() }.joinToString(" ")
                append(mark).append(line).append("\n")
            }
        }

        val exportIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, exportText)
        }
        startActivity(Intent.createChooser(exportIntent, "Export Shopping List"))
    }

    private fun openRecipe(item: UpcomingObject) {
        Toast.makeText(requireContext(), "Open recipe: ${item.primaryText}", Toast.LENGTH_SHORT).show()
    }

    fun editShoppingItem(item: UpcomingObject) {

        Toast.makeText(requireContext(), "Edit dialog called", Toast.LENGTH_SHORT).show()

        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_add_ingredient, null)
        val qty = view.findViewById<EditText>(R.id.inputQuantity)
        val unit = view.findViewById<EditText>(R.id.inputUnit)
        val name = view.findViewById<EditText>(R.id.inputIngredient)
        // Pre-fill
        qty.setText(if (item.quantity % 1 == 0f) item.quantity.toInt().toString() else item.quantity.toString())
        unit.setText(item.primaryText ?: "")
        name.setText(item.secondaryText ?: "")

        AlertDialog.Builder(ctx)
            .setTitle("Edit Shopping Item")
            .setView(view)
            .setPositiveButton("Save") { dialog, _ ->
                val newQty = qty.text.toString().toFloatOrNull() ?: 0f
                val newUnit = unit.text.toString().trim()
                val newName = name.text.toString().trim()
                val updatedItem = item.copy(
                    quantity = newQty,
                    primaryText = newUnit,
                    secondaryText = newName
                )
                // Save to DB
                lifecycleScope.launch {
                    AppDatabase.getInstance(ctx).upcomingDao().insertUpcomingObject(updatedItem)
                    // reload or notify adapter if needed
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun updateShoppingSections(
        recyclerView: RecyclerView,
        deleteButton: Button,
        tutorialLabel: TextView,
        grouped: List<Pair<SectionElement, List<UpcomingObject>>>
    ) {
        val adapters = mutableListOf<RecyclerView.Adapter<*>>()

        for ((section, items) in grouped) {
            val isExpanded = expandedSections.contains(section.id)

            val headerAdapter = SectionHeaderAdapter(
                title = section.title ?: getString(R.string.other_section_label),
                recipeCount = items.size,
                categoryId = section.id,
                isExpanded = isExpanded,
                onArrowClick = {
                    val currentlyExpanded = expandedSections.contains(section.id)
                    if (currentlyExpanded) {
                        expandedSections.remove(section.id)
                    } else {
                        expandedSections.add(section.id)
                    }
                    val newGrouped = viewModel.sections.value ?: return@SectionHeaderAdapter
                    updateShoppingSections(recyclerView, deleteButton, tutorialLabel, newGrouped)
                }
            )
            adapters.add(headerAdapter)

            if (isExpanded) {
                val itemAdapter = UpcomingAdapter(
                    onToggleComplete = { viewModel.toggleComplete(it) },
                    onDelete = { viewModel.deleteItem(it) },
                    onEdit = { editShoppingItem(it) },
                    onOpenRecipe = { openRecipe(it) }
                )
                itemAdapter.submitData(items)
                adapters.add(itemAdapter)
            }
        }

        recyclerView.adapter = androidx.recyclerview.widget.ConcatAdapter(adapters)

        val hasCompleted = grouped.any { (_, items) -> items.any { it.isMarkedAsComplete } }
        deleteButton.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        tutorialLabel.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
    }
}
