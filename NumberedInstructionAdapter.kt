package com.example.rezeptmoment

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class NumberedInstructionAdapter(
    context: Context,
    private val instructions: List<String>
) : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, instructions) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val stepNumber = position + 1
        view.text = "$stepNumber. ${instructions[position]}"
        return view
    }
}