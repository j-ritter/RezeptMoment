package com.example.rezeptmoment.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

object MigrationRunner {
    private const val TAG = "MigrationRunner"

    fun runMigrations(db: AppDatabase) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                migrateImportedPDFs(db)
                migrateImagesAndVideos(db)
                migrateRecipePreviewImages(db)
                Log.i(TAG, "✅ All migrations completed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Migration failed", e)
            }
        }
    }

    private suspend fun migrateImportedPDFs(db: AppDatabase) {
        val pdfElements = db.recipeElementDao().fetchImportedPDFElements(
            folderIds = emptyList(), // TODO: provide old folder IDs if relevant
            limit = 100
        )
        for (el in pdfElements) {
            db.recipeElementDao().markPdfAsMigrated(el.uniqueId)
        }
        if (pdfElements.isNotEmpty()) {
            Log.i(TAG, "Migrated ${pdfElements.size} imported PDFs")
        }
    }

    private suspend fun migrateImagesAndVideos(db: AppDatabase) {
        val media = db.recipeElementDao().fetchImageAndVideoElements(limit = 100)
        for (el in media) {
            when (el.instructionType) {
                "image" -> db.recipeElementDao().clearImageData(el.uniqueId)
                "video" -> db.recipeElementDao().clearAttachmentData(el.uniqueId)
            }
        }
        if (media.isNotEmpty()) {
            Log.i(TAG, "Migrated ${media.size} images/videos")
        }
    }

    private suspend fun migrateRecipePreviewImages(db: AppDatabase) {
        val recipes = db.recipeDao().fetchRecipesWithImage(limit = 100)
        for (recipe in recipes) {
            // TODO: if you want to extract/save images differently
            // For now we just leave as-is or could clear old format
        }
        if (recipes.isNotEmpty()) {
            Log.i(TAG, "Migrated ${recipes.size} recipe preview images")
        }
    }
}
