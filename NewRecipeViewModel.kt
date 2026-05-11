package com.example.rezeptmoment

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rezeptmoment.data.RecipeElement
import kotlinx.coroutines.launch
import java.util.UUID

class NewRecipeViewModel(
    private val recipeId: UUID,
    private val repository: RecipeRepository
) : ViewModel() {

    private val _elementsWithHeaders = MutableLiveData<List<RecipeElement>>()
    val elementsWithHeaders: LiveData<List<RecipeElement>> = _elementsWithHeaders

    fun loadElements() {
        viewModelScope.launch {
            repository.getElementsForRecipe(recipeId).collect { elements ->
                _elementsWithHeaders.postValue(elements)
            }
        }
    }

    fun deleteElement(element: RecipeElement) {
        viewModelScope.launch {
            repository.deleteElement(element)
            loadElements()
        }
    }

    fun swapElements(from: Int, to: Int) {
        // Implement move and persist logic if needed
    }
}
