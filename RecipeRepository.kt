package com.example.rezeptmoment

import com.example.rezeptmoment.data.DidUnlockPremium
import com.example.rezeptmoment.data.PremiumDao
import com.example.rezeptmoment.data.Recipe
import com.example.rezeptmoment.data.RecipeCategory
import com.example.rezeptmoment.data.RecipeCategoryDao
import com.example.rezeptmoment.data.RecipeDao
import com.example.rezeptmoment.data.RecipeElement
import com.example.rezeptmoment.data.RecipeElementDao
import com.example.rezeptmoment.data.UpcomingDao
import com.example.rezeptmoment.data.UpcomingObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val categoryDao: RecipeCategoryDao,
    private val elementDao: RecipeElementDao,
    private val premiumDao: PremiumDao,
    private val upcomingDao: UpcomingDao
) {

    // === Recipe Operations ===
    fun getAllRecipes(): Flow<List<Recipe>> = recipeDao.getAllRecipes()

    fun getMarkedRecipes(): Flow<List<Recipe>> = recipeDao.getMarkedRecipes()

    fun searchRecipes(query: String): Flow<List<Recipe>> = recipeDao.searchRecipes(query)

    fun getCategoriesForUser(userId: String): Flow<List<RecipeCategory>> =
        categoryDao.getCategoriesForUser(userId)

    suspend fun getRecipeById(id: UUID): Recipe? = recipeDao.getRecipeById(id)

    suspend fun insertRecipe(recipe: Recipe) = recipeDao.insertRecipe(recipe)

    suspend fun deleteRecipe(recipe: Recipe) = recipeDao.deleteRecipe(recipe)

    // === Category Operations ===
    fun getAllCategories(): Flow<List<RecipeCategory>> = categoryDao.getAllCategories()

    suspend fun getCategoryById(id: UUID): RecipeCategory? = categoryDao.getCategoryById(id)

    suspend fun getCategoryCount(): Int = categoryDao.getCategoryCount()

    suspend fun insertCategory(category: RecipeCategory) = categoryDao.insertCategory(category)

    suspend fun deleteCategory(category: RecipeCategory) = categoryDao.deleteCategory(category)

    suspend fun getOrCreateDefaultCategory(userId: String): List<RecipeCategory> {
        val existing = categoryDao.getCategoriesForUser(userId).first()
        if (existing.isNotEmpty()) return existing

        val defaultCat = RecipeCategory(
            uniqueId = UUID.randomUUID(),
            primaryText = "Default", // Use your R.string.default_category_name
            orderingIndex = 0,
            userId = userId
        )
        categoryDao.insertCategory(defaultCat)
        return listOf(defaultCat)
    }

    // === Recipe Element Operations ===
    fun getElementsForRecipe(recipeId: UUID): Flow<List<RecipeElement>> =
        elementDao.getElementsForRecipe(recipeId)

    suspend fun insertElement(element: RecipeElement) = elementDao.insertElement(element)

    suspend fun updateElement(element: RecipeElement) = elementDao.updateElement(element)

    suspend fun deleteElement(element: RecipeElement) = elementDao.deleteElement(element)

    suspend fun deleteElementsForRecipe(recipeId: UUID) = elementDao.deleteElementsForRecipe(recipeId)

    // fetch ALL elements (instead of only by recipeId)
    suspend fun getAllElements(): List<RecipeElement> =
        elementDao.getAllElements()

    // Migration-related methods for Recipe Elements
    suspend fun fetchImportedPDFElements(
        folderIds: List<String>,
        instructionType: String,
        limit: Int
    ): List<RecipeElement> =
        elementDao.fetchImportedPDFElements(folderIds, instructionType, limit)


    suspend fun fetchElementsWithEmbeddedPDF(limit: Int): List<RecipeElement> =
        elementDao.fetchElementsWithEmbeddedPDF(limit = limit)

    suspend fun fetchImageAndVideoElements(limit: Int): List<RecipeElement> =
        elementDao.fetchImageAndVideoElements(limit)

    suspend fun markPdfAsMigrated(elementId: UUID) = elementDao.markPdfAsMigrated(elementId)

    suspend fun clearImageData(elementId: UUID) = elementDao.clearImageData(elementId)

    suspend fun clearAttachmentData(elementId: UUID) = elementDao.clearAttachmentData(elementId)

    // === Premium Operations ===
    suspend fun getPremium(): DidUnlockPremium? = premiumDao.getPremium()

    suspend fun insertPremium(premium: DidUnlockPremium) = premiumDao.insertPremium(premium)

    suspend fun clearPremium() = premiumDao.clearPremium()

    // === Upcoming Object Operations ===
    fun getAllUpcomingObjects(): Flow<List<UpcomingObject>> = upcomingDao.getAllUpcomingObjects()

    suspend fun insertUpcomingObject(obj: UpcomingObject) = upcomingDao.insertUpcomingObject(obj)

    suspend fun deleteUpcomingObject(obj: UpcomingObject) = upcomingDao.deleteUpcomingObject(obj)
}
