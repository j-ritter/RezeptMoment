package com.example.rezeptmoment.data

data class EditableIngredient(
    var originalQuantity: Float,
    var unit: String,
    var name: String,
    var baseServings: Long
) {
    override fun toString(): String {
        return "${originalQuantity.trimZero()} $unit $name (for $baseServings)"
    }
}

fun Float.trimZero(): String = if (this % 1.0 == 0.0) this.toInt().toString() else this.toString()


