package com.example.rezeptmoment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class RecipesViewModelFactory(private val repository: RecipeRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return RecipesViewModel(repository) as T
    }
}