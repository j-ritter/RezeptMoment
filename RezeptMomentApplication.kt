package com.example.rezeptmoment

import android.app.Application
import androidx.room.Room
import com.example.rezeptmoment.data.AppDatabase

class RezeptMomentApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "rezeptmoment-db"
        ).build()
    }

    val recipeRepository: RecipeRepository by lazy {
        RecipeRepository(
            database.recipeDao(),
            database.recipeCategoryDao(),
            database.recipeElementDao(),
            database.premiumDao(),
            database.upcomingDao()
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize any application-wide components here
    }
}