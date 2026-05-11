package com.example.rezeptmoment

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rezeptmoment.data.RecipeDao
import com.example.rezeptmoment.data.RecipeElement
import com.example.rezeptmoment.data.UpcomingDao
import com.example.rezeptmoment.data.UpcomingObject
import com.example.rezeptmoment.ui.theme.SectionElement
import com.example.rezeptmoment.ui.util.AppEvent
import com.example.rezeptmoment.ui.util.EventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class UpcomingViewModel(
    private val upcomingDao: UpcomingDao,
    private val recipeDao: RecipeDao
) : ViewModel() {

    private val _sections =
        MutableLiveData<List<Pair<SectionElement, List<UpcomingObject>>>>()
    val sections: LiveData<List<Pair<SectionElement, List<UpcomingObject>>>> = _sections

    companion object {
        val OTHER_SECTION_ID: UUID = UUID(0L, 0L)
    }

    // Handle loading and grouping items
    fun loadItems() {
        viewModelScope.launch {
            upcomingDao.getAllUpcomingObjects().collect { allObjects ->
                val shoppingItems = allObjects.filter { it.type == "shoppingIngredient" }
                val grouped = mutableListOf<Pair<SectionElement, List<UpcomingObject>>>()

                // 1) Recipe sections first
                val recipeGroups = shoppingItems
                    .filter { it.belongsToRecipeId != null }
                    .groupBy { it.belongsToRecipeId }

                for ((recipeId, items) in recipeGroups) {
                    val recipe = recipeId?.let { recipeDao.getRecipeById(it) }
                    recipe?.let {
                        val section = SectionElement(
                            id = it.uniqueId,
                            derivedFromCoreDataObjectId = null,
                            isUndefinedSection = false,
                            title = it.primaryText,
                            isMarked = it.isMarked,
                            orderingIndex = it.orderingIndex
                        )
                        grouped += section to items.sortedBy { obj -> obj.orderingIndex }
                    }
                }

                // 2) "Other" section (no recipe assigned)
                val uncategorized = shoppingItems
                    .filter { it.belongsToRecipeId == null }
                    .sortedBy { it.orderingIndex }
                if (uncategorized.isNotEmpty()) {
                    val section = SectionElement(
                        id = OTHER_SECTION_ID,
                        derivedFromCoreDataObjectId = null,
                        isUndefinedSection = true,
                        title = null,   // Fragment will show localized "Other"
                        isMarked = false,
                        orderingIndex = 0
                    )
                    grouped += section to uncategorized
                }

                _sections.postValue(grouped)
            }
        }
    }

    // Insert from structured dialog (quantity + unit + name)
    fun addUpcomingObjectFromDialog(quantity: Float, unit: String, name: String) {
        viewModelScope.launch {
            val newObj = UpcomingObject(
                uniqueId = UUID.randomUUID(),
                isMarkedAsComplete = false,
                quantity = quantity,
                primaryText = unit,        // unit
                secondaryText = name,      // item name
                orderingIndex = System.currentTimeMillis(),
                type = "shoppingIngredient"
            )
            upcomingDao.insertUpcomingObject(newObj)
            EventBus.post(AppEvent.DidUpdateShoppingList)
        }
    }
    fun toggleRecipeIngredient(ingredient: RecipeElement) {
        viewModelScope.launch {
            // 1. Check if this specific ingredient is already in the shopping list
            val allItems = upcomingDao.getAllUpcomingObjects().first()
            val existingItem = allItems.find {
                it.type == "shoppingIngredient" && it.derivedFromCoreDataObjectId == ingredient.uniqueId
            }

            if (existingItem != null) {
                // 2. If it exists, removeit
                upcomingDao.deleteUpcomingObject(existingItem)
            } else {
                // 3. If it doesn't exist, create a new UpcomingObject
                val newItem = UpcomingObject(
                    uniqueId = UUID.randomUUID(),
                    derivedFromCoreDataObjectId = ingredient.uniqueId, // Link to the recipe ingredient
                    belongsToRecipeId = ingredient.belongsToRecipeId,
                    isMarkedAsComplete = false,
                    quantity = ingredient.quantity,
                    primaryText = ingredient.secondaryText ?: "", // Unit
                    secondaryText = ingredient.primaryText,// Name
                    orderingIndex = System.currentTimeMillis(),
                    type = "shoppingIngredient"
                )
                upcomingDao.insertUpcomingObject(newItem)
            }
            // Notify the rest of the app that the list changed
            EventBus.post(AppEvent.DidUpdateShoppingList)}
    }

    // Flow-based count helper (for badges)
    fun getShoppingItemCountFlow(): Flow<Int> {
        return upcomingDao.getAllUpcomingObjects()
            .map { list -> list.count { it.type == "shoppingIngredient" } }
    }

    // Delete marked-complete items
    fun deleteCompleted() {
        viewModelScope.launch {
            val allObjects = upcomingDao.getAllUpcomingObjects().first()
            allObjects.filter { it.isMarkedAsComplete }.forEach {
                upcomingDao.deleteUpcomingObject(it)
                EventBus.post(AppEvent.DidUpdateShoppingList)
            }
        }
    }

    // Mark item complete/incomplete
    fun toggleComplete(item: UpcomingObject) {
        viewModelScope.launch {
            upcomingDao.insertUpcomingObject(
                item.copy(isMarkedAsComplete = !item.isMarkedAsComplete)
            )
            EventBus.post(AppEvent.DidUpdateShoppingList)
        }
    }

    // Delete individual object
    fun deleteItem(item: UpcomingObject) {
        viewModelScope.launch {
            upcomingDao.deleteUpcomingObject(item)
            EventBus.post(AppEvent.DidUpdateShoppingList)
        }
    }
}
