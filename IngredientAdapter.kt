package com.example.rezeptmoment

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.ImageView
import com.example.rezeptmoment.data.EditableIngredient

class IngredientAdapter(
    private val ingredients: List<EditableIngredient>,
    private val getCurrentPeople: () -> Long,
    private val onIngredientClick: (Int) -> Unit

) : BaseAdapter() {
    override fun getCount() = ingredients.size
    override fun getItem(position: Int) = ingredients[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.ingredient_row, parent, false)

        val ingredient = getItem(position)
        val scaled = ingredient.getScaledQuantity(getCurrentPeople())

        view.findViewById<TextView>(R.id.tvQuantity).text = scaled.trimZero()
        view.findViewById<TextView>(R.id.tvUnit).text = ingredient.unit
        view.findViewById<TextView>(R.id.tvName).text = ingredient.name

        view.setOnClickListener { onIngredientClick(position) }


        return view
    }
}

fun EditableIngredient.getScaledQuantity(currentPeople: Long): Float {
    return if (baseServings > 0)
        originalQuantity * currentPeople.toFloat() / baseServings
    else originalQuantity
}

private fun Float.trimZero(): String = if (this % 1.0f == 0.0f) this.toInt().toString() else this.toString()
