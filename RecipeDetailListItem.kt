package com.example.rezeptmoment

import com.example.rezeptmoment.data.RecipeElement

sealed class RecipeDetailListItem {
    data class Header(
        val imageBytes: ByteArray?,
        val title: String,
        val servings: Int?,
        val durationMinutes: Int?
    ) : RecipeDetailListItem()

    object IngredientsHeader : RecipeDetailListItem()

    // ADD recipeElement to Ingredient
    data class Ingredient(
        val name: String,
        val quantity: Float,
        val unit: String?,
        val recipeElement: RecipeElement
    ) : RecipeDetailListItem()

    object StepsHeader : RecipeDetailListItem()

    // ADD recipeElement to Step
    data class Step(
        val index: Int,
        val description: String,
        val imageBytes: ByteArray?,
        val isMedia: Boolean,
        val recipeElement: RecipeElement
    ) : RecipeDetailListItem()
}
