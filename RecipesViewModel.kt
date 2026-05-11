package com.example.rezeptmoment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rezeptmoment.data.Recipe
import com.example.rezeptmoment.data.RecipeCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class RecipesViewModel(private val repository: RecipeRepository) : ViewModel() {

    private val _categories = MutableStateFlow<List<RecipeCategory>>(emptyList())
    val categories: StateFlow<List<RecipeCategory>> = _categories.asStateFlow()

    fun loadCategoriesForUser(email: String) {
        if (email.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Check thecurrent state ONCE
            val currentCategories = repository.getCategoriesForUser(email).first()

            if (currentCategories.isEmpty()) {
                // 2. Create a deterministic UUID based on the email
                // so it's always the same ID for the same user.
                val defaultId = UUID.nameUUIDFromBytes("default_cat_$email".toByteArray())

                val defaultCat = RecipeCategory(
                    uniqueId = defaultId,primaryText = "My Recipe",
                    orderingIndex = 0,
                    userId = email)
                repository.insertCategory(defaultCat)
            }

            repository.getCategoriesForUser(email).collect { updatedList ->
                _categories.value = updatedList
            }}
    }


    val allRecipes: Flow<List<Recipe>> = repository.getAllRecipes()
    val markedRecipes: Flow<List<Recipe>> = repository.getMarkedRecipes()

    fun searchRecipes(query: String): Flow<List<Recipe>> = repository.searchRecipes(query)

    suspend fun createRecipe(recipe: Recipe) = repository.insertRecipe(recipe)
    suspend fun deleteRecipe(recipe: Recipe) = repository.deleteRecipe(recipe)
}