package com.example.rezeptmoment.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import com.example.rezeptmoment.data.Recipe
import java.util.UUID

class ExportListAdapter(
    private val onToggle: (UUID, Boolean) -> Unit
) : RecyclerView.Adapter<ExportListAdapter.Holder>() {

    private val items = mutableListOf<Recipe>()
    private val selected = linkedSetOf<UUID>()

    fun submit(list: List<Recipe>, selectedIds: Set<UUID>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selected.addAll(selectedIds)
        notifyDataSetChanged()
    }

    fun currentSelection(): Set<UUID> = selected.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_export_recipe_row, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val r = items[pos]
        h.title.text = r.primaryText ?: h.itemView.context.getString(R.string.untitled_recipe)
        val id = r.uniqueId
        val checked = selected.contains(id)
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = checked
        h.itemView.setOnClickListener { h.check.toggle() }
        h.check.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(id) else selected.remove(id)
            onToggle(id, isChecked)
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
    }
}
