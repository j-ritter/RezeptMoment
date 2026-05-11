package com.example.rezeptmoment.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.R
import java.util.*

class SectionHeaderAdapter(
    private val title: String,
    private val recipeCount: Int,
    private val categoryId: UUID,  // For context if needed
    private val isExpanded: Boolean,
    private val onArrowClick: (() -> Unit)? = null
) : RecyclerView.Adapter<SectionHeaderAdapter.Holder>() {

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleView: TextView = itemView.findViewById(R.id.sectionTitleRecipeDetail)
        val countView: TextView = itemView.findViewById(R.id.categoryRecipeCountRecipeDetail)
        val arrowView: ImageView = itemView.findViewById(R.id.arrowIconRecipeDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_header, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.titleView.text = title
        holder.countView.text = "($recipeCount)"
        holder.arrowView.setImageResource(
            if (isExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right
        )
        holder.arrowView.setOnClickListener { onArrowClick?.invoke() }
        holder.itemView.setOnClickListener { onArrowClick?.invoke() }
    }

    override fun getItemCount(): Int = 1
}
