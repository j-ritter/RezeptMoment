package com.example.rezeptmoment

import android.content.Context
import android.graphics.BitmapFactory
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rezeptmoment.data.AppDatabase
import com.example.rezeptmoment.data.Recipe

import java.io.File

class RecipesAdapter(
    private val onClick: (Recipe) -> Unit,
    private val onLongPress: ((Recipe, View) -> Unit)? = null
) : ListAdapter<Recipe, RecipesAdapter.VH>(Diff) {

    private var highlightQuery: String? = null

    fun setHighlightQuery(query: String?) {
        highlightQuery = query?.lowercase()
        notifyDataSetChanged()
    }

    object Diff : DiffUtil.ItemCallback<Recipe>() {
        override fun areItemsTheSame(oldItem: Recipe, newItem: Recipe) =
            oldItem.uniqueId == newItem.uniqueId

        override fun areContentsTheSame(oldItem: Recipe, newItem: Recipe) =
            oldItem == newItem
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.titleText)
        private val image: ImageView = v.findViewById(R.id.thumbImage)
        private val shoppingBadge: TextView = v.findViewById(R.id.shoppingBadge)
        private val markedBadge: ImageView? = v.findViewById(R.id.markedBadge)

        fun bind(item: Recipe) {
            val context = itemView.context

            // Reset state
            title.text = ""
            image.setImageDrawable(null)
            shoppingBadge.visibility = View.GONE
            markedBadge?.visibility = View.GONE

            // Shopping badge:
            val badgeCount = item.shoppingItemCount ?: 0
            if (badgeCount > 0) {
                shoppingBadge.visibility = View.VISIBLE
                shoppingBadge.text = badgeCount.toString()
            } else {
                shoppingBadge.visibility = View.GONE
            }



            // Large design toggle
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            title.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (prefs.getBoolean("useLargeDesign", false)) 22f else 16f
            )

            // Highlight search
            val text = item.primaryText ?: ""
            val highlight = highlightQuery
            if (!highlight.isNullOrEmpty()) {
                val lowerText = text.lowercase()
                val start = lowerText.indexOf(highlight)
                if (start >= 0) {
                    val spannable = android.text.SpannableString(text)
                    val end = start + highlight.length
                    val color = ContextCompat.getColor(context, R.color.tertiary_light)
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(color),
                        start,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    title.text = spannable
                } else {
                    title.text = text
                }
            } else {
                title.text = text
            }

            // Load image from disk (preferred) or from DB bytes, else placeholder
            val uuid = item.uniqueId?.toString()
            val file = File(context.filesDir, "$uuid.jpg")
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                image.setImageBitmap(bmp)
            } else {
                val img = item.image
                if (img != null) {
                    val bmp = BitmapFactory.decodeByteArray(img, 0, img.size)
                    image.setImageBitmap(bmp)
                } else {
                    image.setImageResource(R.drawable.ic_camera)
                }
            }

            // Marked state
            if (item.isMarked) {
                markedBadge?.visibility = View.VISIBLE
            } else {
                markedBadge?.visibility = View.GONE
            }

            // Shopping list state (from model)
            shoppingBadge.visibility = if (item.hasShoppingItems) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onLongPress?.let { it(item, itemView); true } ?: false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recipe, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}
