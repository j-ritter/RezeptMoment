package com.example.rezeptmoment.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DatabaseInitializer {
    fun initializeAndMigrate(context: Context, completion: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            // Access a simple query to force the DB to open and finish migration
            db.recipeDao().getRecipeCount()

            // Now safe to run custom utils
            MigrationUtils.performAllMigrations(context, db.recipeElementDao(), db.recipeDao())

            withContext(Dispatchers.Main) { completion() }
        }
    }
}