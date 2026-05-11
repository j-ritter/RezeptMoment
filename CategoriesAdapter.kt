package com.example.rezeptmoment.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import com.example.rezeptmoment.data.RecipeCategory
import java.util.Collections
import java.util.UUID

class CategoriesAdapter(
    private val onMenuClicked: (RecipeCategory, View) -> Unit
) : RecyclerView.Adapter<CategoriesAdapter.VH>() {

    val items: MutableList<RecipeCategory> = mutableListOf()

    private var recipeCounts: Map<UUID, Int> = emptyMap()

    fun setRecipeCounts(counts: Map<UUID, Int>) {
        recipeCounts = counts
        notifyDataSetChanged()
    }

    fun submitList(list: List<RecipeCategory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.categoryName)
        private val subtitle: TextView = v.findViewById(R.id.categorySubtitle)
        private val more: ImageButton = v.findViewById(R.id.buttonMore)

        fun bind(item: RecipeCategory) {
            name.text = item.primaryText
            subtitle?.let { tv ->
                val count = recipeCounts[item.uniqueId] ?: 0
                if (count > 0) {
                    tv.text = itemView.context.getString(R.string.n_recipes, count)
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            }
            more.setOnClickListener { onMenuClicked(item, more) }
            // No add/edit button in current item_category.xml
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
