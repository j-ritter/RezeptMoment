package com.example.rezeptmoment.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import com.example.rezeptmoment.data.MigrationUtils
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.rezeptmoment.ui.theme.Converters
import com.example.rezeptmoment.data.RecipeCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors


@Database(
    entities = [
        UpcomingObject::class,
        Recipe::class,
        RecipeElement::class,
        DidUnlockPremium::class,
        RecipeCategory::class,
        User::class
    ],

    version = 14
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun upcomingDao(): UpcomingDao
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeElementDao(): RecipeElementDao
    abstract fun premiumDao(): PremiumDao
    abstract fun recipeCategoryDao(): RecipeCategoryDao
    abstract fun userDao(): UserDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `RecipeCategory` (
                        `uniqueId` TEXT NOT NULL,
                        `primaryText` TEXT NOT NULL,
                        `orderingIndex` INTEGER NOT NULL,
                        PRIMARY KEY(`uniqueId`)
                    )
                    """.trimIndent()
                )
            }
        }
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to RecipeElement with safe defaults
                // quantity: REAL NOT NULL DEFAULT 0
                database.execSQL(
                    "ALTER TABLE `RecipeElement` ADD COLUMN `quantity` REAL NOT NULL DEFAULT 0"
                )
                // secondaryText: TEXT (nullable)
                database.execSQL(
                    "ALTER TABLE `RecipeElement` ADD COLUMN `secondaryText` TEXT"
                )
                // numberOfCurrentPeople: INTEGER (nullable)
                database.execSQL(
                    "ALTER TABLE `RecipeElement` ADD COLUMN `numberOfCurrentPeople` INTEGER"
                )
            }
        }

        // ⬇️ New, pass-through migration (schema identity update)
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add numberOfPersons to Recipe with a safe default (1)
                database.execSQL(
                    "ALTER TABLE `Recipe` ADD COLUMN `numberOfPersons` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // boolean -> INTEGER 0/1
                db.execSQL("ALTER TABLE `Recipe` ADD COLUMN `isPDFbasedRecipe` INTEGER NOT NULL DEFAULT 0")
                // nullable; if absent use numberOfPersons at runtime
                db.execSQL("ALTER TABLE `Recipe` ADD COLUMN `currentlySelectedNumberOfPersons` INTEGER")
            }
        }
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add linkedIngredientId column (nullable TEXT for UUID)
                database.execSQL(
                    "ALTER TABLE `UpcomingObject` ADD COLUMN `linkedIngredientId` TEXT"
                )
            }
        }
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `UpcomingObject` ADD COLUMN `quantity` REAL NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes needed - just adding new queries using existing linkedIngredientId column
            }
        }
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            ALTER TABLE UpcomingObject 
            ADD COLUMN derivedFromCoreDataObjectId TEXT
        """.trimIndent())
            }
        }
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
            CREATE TABLE IF NOT EXISTS `users` (
                `email` TEXT NOT NULL PRIMARY KEY,
                `passwordHash` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
        """.trimIndent())
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // ✅ Step 1: Add nullable userId column
                database.execSQL("ALTER TABLE Recipe ADD COLUMN userId TEXT")

                // ✅ Step 2: Assign existing recipes to FIRST created user (or null if no users)
                database.execSQL("""
            UPDATE Recipe 
            SET userId = COALESCE(
                (SELECT email FROM users ORDER BY createdAt ASC LIMIT 1),
                'shared'
            )
            WHERE userId IS NULL
        """.trimIndent())

                // ✅ Step 3: Make column NOT NULL by creating new table
                database.execSQL("""
            CREATE TABLE Recipe_new (
                uniqueId TEXT PRIMARY KEY NOT NULL,
                primaryText TEXT,
                belongsToCategoryId TEXT,
                orderingIndex INTEGER NOT NULL,
                isMarked INTEGER NOT NULL,
                dateOfMarking INTEGER,
                image BLOB,
                isPDFbasedRecipe INTEGER NOT NULL DEFAULT 0,
                currentlySelectedNumberOfPersons INTEGER,
                numberOfPersons INTEGER NOT NULL DEFAULT 1,
                shoppingItemCount INTEGER,
                userId TEXT NOT NULL
            )
        """.trimIndent())

                // ✅ Step 4: Copy ALL data (including new userId)
                database.execSQL("""
            INSERT INTO Recipe_new 
            SELECT * FROM Recipe
        """.trimIndent())

                // ✅ Step 5: Replace tables
                database.execSQL("DROP TABLE Recipe")
                database.execSQL("ALTER TABLE Recipe_new RENAME TO Recipe")

                // ✅ Step 6: Add foreign key constraint
                database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_recipe_userId ON Recipe(userId)
        """.trimIndent())
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE RecipeCategory ADD COLUMN userId TEXT")
                database.execSQL("""
            UPDATE RecipeCategory 
            SET userId = COALESCE(
                (SELECT email FROM users ORDER BY createdAt ASC LIMIT 1),
                'shared'
            )
            WHERE userId IS NULL
        """.trimIndent())
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Remove problematic foreign key index first (if exists)
                database.execSQL("DROP INDEX IF EXISTS `index_recipe_userId`")

                // Ensure userId is populated (safety)
                database.execSQL("""
                    UPDATE Recipe 
                    SET userId = COALESCE(
                        (SELECT email FROM users ORDER BY createdAt ASC LIMIT 1),
                        'shared'
                    )
                    WHERE userId IS NULL
                """.trimIndent())

                // Recreate simple index (no foreign key constraint)
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recipe_userId ON Recipe(userId)
                """.trimIndent())
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Drop and recreate completely
                database.execSQL("DROP TABLE IF EXISTS Recipe")
                // Re-create the EXACT table from the entity class
                database.execSQL("""
            CREATE TABLE Recipe (
                uniqueId TEXT PRIMARY KEY NOT NULL,
                primaryText TEXT,
                belongsToCategoryId TEXT,
                orderingIndex INTEGER NOT NULL,
                isMarked INTEGER NOT NULL,
                dateOfMarking INTEGER,
                image BLOB,
                isPDFbasedRecipe INTEGER NOT NULL DEFAULT 0,
                currentlySelectedNumberOfPersons INTEGER,
                numberOfPersons INTEGER NOT NULL DEFAULT 1,
                shoppingItemCount INTEGER,
                userId TEXT NOT NULL
            )
        """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rezeptmoment.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14 )
                    .setQueryCallback({ sqlQuery, bindArgs ->
                        android.util.Log.d("RoomSQL", "SQL: $sqlQuery ARGS: ${bindArgs.joinToString()}")
                    }, Executors.newSingleThreadExecutor()) // <-- log every Room SQL!
                    .build()


                INSTANCE = instance
                instance
            }
        }
        fun runDatabaseMigrations(context: Context) {
            val prefs = context.getSharedPreferences("RezeptmomentPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("did_run_migrations", false)) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = getInstance(context)
                    // 1. Ensure Room is fully initialized
                    db.recipeDao().getRecipeCount()

                    // 2. Run your existing MigrationUtils logic
                    MigrationUtils.performAllMigrations(
                        context = context,
                        recipeElementDao = db.recipeElementDao(),
                        recipeDao = db.recipeDao(),
                        progressListener = null
                    )

                    // 3. Mark as finished so it NEVER runs again
                    prefs.edit().putBoolean("did_run_migrations", true).apply()
                } catch (e: Exception) {
                    android.util.Log.e("AppDatabase", "Background migration failed", e)
                }
            }
        }
    }
}

/* ======================= DAOs ======================= */

@Dao
interface UpcomingDao {
    @Query("SELECT * FROM UpcomingObject")
    fun getAllUpcomingObjects(): Flow<List<UpcomingObject>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpcomingObject(obj: UpcomingObject)

    // ✅ ADD THIS - MISSING METHOD
    @Query("DELETE FROM UpcomingObject WHERE linkedIngredientId IN (:ingredientIds)")
    suspend fun deleteUpcomingForIngredientIds(ingredientIds: List<UUID>)

    @Delete
    suspend fun deleteUpcomingObject(obj: UpcomingObject)

    @Query("SELECT COUNT(*) FROM UpcomingObject WHERE belongsToRecipeId = :recipeId AND type = 'shoppingIngredient'")
    suspend fun countShoppingItemsForRecipe(recipeId: UUID): Int

    @Query("DELETE FROM UpcomingObject")
    suspend fun deleteAll()

    @Query("SELECT * FROM UpcomingObject WHERE linkedIngredientId = :ingredientId")
    suspend fun getByIngredientId(ingredientId: UUID): List<UpcomingObject>

    @Query("SELECT COUNT(*) FROM UpcomingObject WHERE type = 'shoppingIngredient'")
    suspend fun countAllShoppingItems(): Int

    @Query("SELECT COUNT(*) FROM UpcomingObject WHERE type = 'shoppingIngredient' AND isMarkedAsComplete = 0 AND belongsToRecipeId IS NOT NULL")
    suspend fun countPendingShoppingItems(): Int

}



@Dao
interface RecipeDao {
    @Query("SELECT * FROM Recipe WHERE uniqueId = :id LIMIT 1")
    suspend fun getRecipeById(id: UUID): Recipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: Recipe)

    @Query("SELECT * FROM Recipe")  // Added missing query
    fun getAllRecipes(): Flow<List<Recipe>>

    @Query("SELECT * FROM Recipe WHERE isMarked = 1 ORDER BY dateOfMarking ASC")  // Added for marked recipes
    fun getMarkedRecipes(): Flow<List<Recipe>>

    @Query("SELECT * FROM Recipe WHERE primaryText LIKE '%' || :query || '%'")  // Added for search
    fun searchRecipes(query: String): Flow<List<Recipe>>

    @Query("""
    SELECT Recipe.* FROM Recipe
    LEFT JOIN RecipeCategory ON Recipe.belongsToCategoryId = RecipeCategory.uniqueId
    WHERE (Recipe.primaryText LIKE '%' || :query || '%')
       OR (RecipeCategory.primaryText LIKE '%' || :query || '%')
""")
    fun searchRecipesWithCategory(query: String): Flow<List<Recipe>>

    @Query("""
    SELECT DISTINCT Recipe.* 
    FROM Recipe
    LEFT JOIN RecipeCategory ON Recipe.belongsToCategoryId = RecipeCategory.uniqueId
    LEFT JOIN RecipeElement ON Recipe.uniqueId = RecipeElement.belongsToRecipeId
    WHERE (Recipe.primaryText LIKE '%' || :query || '%')
       OR (RecipeCategory.primaryText LIKE '%' || :query || '%')
       OR (RecipeElement.instructionText LIKE '%' || :query || '%')
""")

    fun searchRecipesFull(query: String): Flow<List<Recipe>>

    @Delete  // Added delete
    suspend fun deleteRecipe(recipe: Recipe)

    // alias used by fragment
    @Delete
    suspend fun delete(recipe: Recipe)

    @Query("UPDATE Recipe SET belongsToCategoryId = NULL WHERE belongsToCategoryId = :categoryId")
    suspend fun uncategorizeRecipes(categoryId: UUID)

    @Query("SELECT * FROM Recipe WHERE image IS NOT NULL LIMIT :limit")
    suspend fun fetchRecipesWithImage(limit: Int): List<Recipe>

    @Update
    suspend fun updateRecipe(recipe: Recipe)

    // alias used by fragment
    @Update
    suspend fun update(recipe: Recipe)


    @Query("SELECT COUNT(*) FROM Recipe")
    suspend fun getRecipeCount(): Int

    @Query("DELETE FROM Recipe WHERE uniqueId = :id")
    suspend fun deleteRecipeById(id: UUID)

    @Query("SELECT * FROM Recipe WHERE userId = :userId")
    fun getRecipesForUser(userId: String): Flow<List<Recipe>>

    @Query("UPDATE Recipe SET image = NULL WHERE uniqueId = :id")
    suspend fun clearRecipeImage(id: UUID)

}

@Dao
interface PremiumDao {
    @Query("SELECT * FROM DidUnlockPremium LIMIT 1")
    suspend fun getPremium(): DidUnlockPremium?

    @Insert
    suspend fun insertPremium(premium: DidUnlockPremium)

    @Query("DELETE FROM DidUnlockPremium")
    suspend fun clearPremium()
}

@Dao
interface RecipeCategoryDao {

    @Query("SELECT * FROM RecipeCategory WHERE userId = :userId ORDER BY orderingIndex ASC")
    fun getCategoriesForUser(userId: String): Flow<List<RecipeCategory>>

    @Query("SELECT * FROM RecipeCategory ORDER BY orderingIndex ASC")
    fun getAllCategories(): Flow<List<RecipeCategory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: RecipeCategory)

    @Query("SELECT * FROM RecipeCategory WHERE uniqueId = :id LIMIT 1")
    suspend fun getCategoryById(id: UUID): RecipeCategory?

    @Query("SELECT COUNT(*) FROM RecipeCategory")
    suspend fun getCategoryCount(): Int

    @Update
    suspend fun updateCategory(category: RecipeCategory)

    @Update
    suspend fun updateCategories(categories: List<RecipeCategory>)

    @Delete
    suspend fun deleteCategory(category: RecipeCategory)
}


@Dao
interface RecipeElementDao {
    // --- SELECT queries for migrations ---

    // 1) Imported PDFs still stored in recipe folders
    @Query(
        """
        SELECT * FROM RecipeElement
        WHERE belongsToRecipeId IN (:folderIds)
          AND instructionType = :instructionType
          AND uniqueId IS NOT NULL
        ORDER BY uniqueId
        LIMIT :limit
    """
    )
    suspend fun fetchImportedPDFElements(
        folderIds: List<String>,
        instructionType: String = "importedPDF",
        limit: Int
    ): List<RecipeElement>

    @Query("SELECT * FROM RecipeElement")
    suspend fun getAllElements(): List<RecipeElement>


    // 2) Elements with embedded PDF blobs
    @Query(
        """
        SELECT * FROM RecipeElement
        WHERE instructionType = :instructionType
          AND uniqueId IS NOT NULL
          AND attachmentData IS NOT NULL
        ORDER BY uniqueId
        LIMIT :limit
    """
    )
    suspend fun fetchElementsWithEmbeddedPDF(
        instructionType: String = "pdf",
        limit: Int
    ): List<RecipeElement>

    // 3) Elements with image/video blobs
    @Query(
        """
        SELECT * FROM RecipeElement
        WHERE uniqueId IS NOT NULL
          AND (
              (instructionType = 'image' AND imageData IS NOT NULL) OR
              (instructionType = 'video' AND attachmentData IS NOT NULL) OR
              (type = 'image' AND imageData IS NOT NULL)
          )
        ORDER BY uniqueId
        LIMIT :limit
    """
    )
    suspend fun fetchImageAndVideoElements(limit: Int): List<RecipeElement>

    // --- Generic CRUD operations ---
    @Query("SELECT * FROM RecipeElement WHERE belongsToRecipeId = :recipeId ORDER BY orderingIndex ASC")
    fun getElementsForRecipe(recipeId: UUID): Flow<List<RecipeElement>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertElement(element: RecipeElement)

    @Update
    suspend fun updateElement(element: RecipeElement)

    @Delete
    suspend fun deleteElement(element: RecipeElement)

    @Query("DELETE FROM RecipeElement WHERE belongsToRecipeId = :recipeId")
    suspend fun deleteElementsForRecipe(recipeId: UUID)

    // --- UPDATE queries for clearing migrated blobs and fixing types ---

    // Clear PDF blob and mark as importedPDF
    @Query(
        """
        UPDATE RecipeElement
        SET attachmentData = NULL,
            instructionType = 'importedPDF'
        WHERE uniqueId = :elementId
    """
    )
    suspend fun markPdfAsMigrated(elementId: UUID)  // Changed to UUID

    // Clear image blob after migration
    @Query(
        """
        UPDATE RecipeElement
        SET imageData = NULL
        WHERE uniqueId = :elementId
    """
    )
    suspend fun clearImageData(elementId: UUID)  // Changed to UUID

    // Clear video blob after migration
    @Query(
        """
        UPDATE RecipeElement
        SET attachmentData = NULL
        WHERE uniqueId = :elementId
    """
    )
    suspend fun clearAttachmentData(elementId: UUID)  // Changed to UUID
}
@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<User>>
}
