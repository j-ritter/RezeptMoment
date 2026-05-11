package com.example.rezeptmoment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.data.UpcomingObject
import androidx.recyclerview.widget.DiffUtil              // *** CHANGED: import
import androidx.recyclerview.widget.ListAdapter

class UpcomingAdapter(
    private val onToggleComplete: (UpcomingObject) -> Unit,
    private val onDelete: (UpcomingObject) -> Unit,
    private val onEdit: ((UpcomingObject) -> Unit)? = null,
    private val onOpenRecipe: ((UpcomingObject) -> Unit)? = null
) : ListAdapter<UpcomingObject, UpcomingAdapter.ItemViewHolder>(DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UpcomingObject>() {
            override fun areItemsTheSame(oldItem: UpcomingObject, newItem: UpcomingObject): Boolean {
                return oldItem.uniqueId == newItem.uniqueId
            }

            override fun areContentsTheSame(oldItem: UpcomingObject, newItem: UpcomingObject): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun submitData(items: List<UpcomingObject>) {
        submitList(items)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_upcoming_object, parent, false)
        return ItemViewHolder(v)
    }


    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(
            getItem(position),
            onToggleComplete,
            onDelete,
            onEdit,
            onOpenRecipe
        )
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.itemIcon)
        private val title: TextView = view.findViewById(R.id.itemTitle)
        private val secondary: TextView = view.findViewById(R.id.itemSecondaryText)

        fun bind(
            item: UpcomingObject,
            toggle: (UpcomingObject) -> Unit,
            delete: (UpcomingObject) -> Unit,
            onEdit: ((UpcomingObject) -> Unit)?,
            openRecipe: ((UpcomingObject) -> Unit)?
        ) {
            val qty = if (item.quantity != 0f) {
                if (item.quantity % 1 == 0f) item.quantity.toInt().toString() else item.quantity.toString()
            } else ""
            val unit = item.primaryText?.trim().orEmpty()
            val name = item.secondaryText?.trim().orEmpty()

            val displayString = listOf(qty, unit, name)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            title.text = displayString
            secondary.visibility = View.GONE

            icon.setImageResource(
                if (item.isMarkedAsComplete) R.drawable.ic_check else R.drawable.ic_circle
            )
            icon.setColorFilter(
                if (item.isMarkedAsComplete) {
                    itemView.context.getColor(R.color.primary_light)
                } else {
                    itemView.context.getColor(R.color.unselected_item_color)
                }
            )

            itemView.setOnClickListener { toggle(item) }
            itemView.setOnLongClickListener {
                val popup = PopupMenu(itemView.context, itemView)
                if (item.belongsToRecipeId != null && openRecipe != null) {
                    popup.menu.add("Open Recipe").setOnMenuItemClickListener {
                        openRecipe(item)
                        true
                    }
                }
                popup.menu.add("Edit").setOnMenuItemClickListener {
                    Toast.makeText(itemView.context, "Edit clicked", Toast.LENGTH_SHORT).show()
                    onEdit?.invoke(item)
                    true
                }
                popup.menu.add("Remove").setOnMenuItemClickListener {
                    delete(item)
                    true
                }
                popup.show()
                true
            }
        }
    }
}