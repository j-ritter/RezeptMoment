package com.example.rezeptmoment.data

import android.content.Context
import android.util.Log
import com.example.rezeptmoment.data.RecipeElement
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.CoroutineContext

interface MigrationProgressListener {
    fun onStart(labelText: String)
    fun onProgress(totalMigrated: Int, labelText: String)
    fun onComplete()
    fun onError(e: Throwable)
}

object MigrationUtils {
    private const val TAG = "MigrationUtils"

    /**
     * Master migration function — runs sequentially the smaller migrations.
     * Accepts DAOs so you can pass your AppDatabase.recipeElementDao() etc.
     */
    fun performAllMigrations(
        context: Context,
        recipeElementDao: RecipeElementDao,
        recipeDao: RecipeDao,
        progressListener: MigrationProgressListener? = null,
        completion: (() -> Unit)? = null,
        dispatcher: CoroutineContext = Dispatchers.IO
    ) {
        progressListener?.onStart("Updating...")

        CoroutineScope(dispatcher).launch {
            try {
                migrateRecipePreviewImages(context, recipeDao, 50, progressListener)
                migrateImportedPDFs(context, recipeElementDao, 50, progressListener)
                migrateOtherPDFs(context, recipeElementDao, 50, progressListener)
                migrateImagesAndVideos(context, recipeElementDao, 50, progressListener)
            } catch (e: Throwable) {
                Log.e(TAG, "Migration error", e)
                withContext(Dispatchers.Main) { progressListener?.onError(e) }
            } finally {
                withContext(Dispatchers.Main) {
                    progressListener?.onComplete()
                    completion?.invoke()
                }
            }
        }
    }

    /**
     * migrateImportedPDFs: move files out of recipe folder named by UUID into root documentsDir
     * and rename them to "<elementUniqueId>.pdf"
     */
    suspend fun migrateImportedPDFs(
        context: Context,
        dao: RecipeElementDao,
        batchSize: Int = 50,
        progressListener: MigrationProgressListener? = null
    ) = withContext(Dispatchers.IO) {
        val documentsDir: File = context.filesDir
        val fileManager = documentsDir
        var totalMigrated = 0

        while (true) {
            val folderFiles = documentsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val folderNames = folderFiles.mapNotNull { f ->
                try {
                    UUID.fromString(f.name)
                    f.name
                } catch (e: Exception) {
                    null
                }
            }
            if (folderNames.isEmpty()) break

            val elements = try {
                dao.fetchImportedPDFElements(folderNames, "importedPDF", batchSize)
            } catch (e: Exception) {
                Log.e(TAG, "DB fetch error in migrateImportedPDFs", e)
                break
            }

            if (elements.isEmpty()) break

            for (element in elements) {
                try {
                    val recipeId = element.belongsToRecipeId ?: continue
                    val elementId = element.uniqueId ?: continue
                    val oldFolder = File(documentsDir, recipeId.toString())
                    if (!oldFolder.exists()) continue
                    val filesInFolder = oldFolder.listFiles() ?: emptyArray()
                    if (filesInFolder.isEmpty()) {
                        if (oldFolder.listFiles()?.isEmpty() ?: true) oldFolder.deleteRecursively()
                        continue
                    }
                    val oldPdf = filesInFolder.first()
                    val newFileName = "${elementId}.pdf"
                    val newFile = File(documentsDir, newFileName)
                    if (newFile.exists()) newFile.delete()
                    oldPdf.copyTo(newFile, overwrite = true)
                    if (!oldPdf.delete()) Log.w(TAG, "Failed to delete source ${oldPdf.path}")
                    if ((oldFolder.listFiles()?.isEmpty() ?: true)) {
                        oldFolder.deleteRecursively()
                    }
                    totalMigrated++
                    withContext(Dispatchers.Main) {
                        progressListener?.onProgress(totalMigrated, "PDFs: $totalMigrated")
                    }
                    Log.d(TAG, "Moved ${oldPdf.name} -> ${newFile.path}")
                } catch (inner: Exception) {
                    Log.e(TAG, "Error processing element in migrateImportedPDFs", inner)
                }
            }
        }
        Log.d(TAG, "PDF migration completed. Total migrated: $totalMigrated.")
        withContext(Dispatchers.Main) {
            progressListener?.onProgress(totalMigrated, "PDFs: $totalMigrated")
        }
    }

    /**
     * migrateOtherPDFs: elements that stored embedded PDF bytes -> write files and update DB state
     * NOTE: This function writes files but DOES NOT update the DB row (Room update must be invoked).
     * You should add a DAO update/insert to clear attachmentData and set instructionType="importedPDF"
     * if you want parity with the iOS code.
     */
    // === migrateOtherPDFs ===
    suspend fun migrateOtherPDFs(
        context: Context,
        dao: RecipeElementDao,
        batchSize: Int = 50,
        progressListener: MigrationProgressListener? = null
    ) = withContext(Dispatchers.IO) {
        val documentsDir = context.filesDir
        var totalMigrated = 0

        while (true) {
            val pdfElements = try {
                dao.fetchElementsWithEmbeddedPDF("pdf", batchSize)
            } catch (e: Exception) {
                Log.e(TAG, "DB fetch error in migrateOtherPDFs", e); break
            }
            if (pdfElements.isEmpty()) break

            for (element in pdfElements) {
                try {
                    val elementId = element.uniqueId ?: continue
                    val pdfData = element.attachmentData ?: continue
                    val newFileName = "${elementId}.pdf"
                    val newFile = File(documentsDir, newFileName)
                    if (newFile.exists()) newFile.delete()
                    newFile.outputStream().use { it.write(pdfData) }
                    Log.d(TAG, "Stored PDF for element $elementId at ${newFile.path}")

                    // ✅ Clear DB fields like iOS
                    element.attachmentData = null
                    element.instructionType = "importedPDF"
                    dao.updateElement(element)   // <--- Room update

                    totalMigrated++
                    withContext(Dispatchers.Main) {
                        progressListener?.onProgress(totalMigrated, "PDFs: $totalMigrated")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing embedded pdf for element ${element.uniqueId}", e)
                }
            }
        }
        Log.d(TAG, "Other-PDF migration completed. Total migrated: $totalMigrated.")
        withContext(Dispatchers.Main) {
            progressListener?.onProgress(totalMigrated, "PDFs: $totalMigrated")
        }
    }


    /**
     * migrateImagesAndVideos: similar to the iOS method; write image jpgs or video files to disk,
     * clear the DB blob fields afterwards (requires DAO update).
     */
    // === migrateImagesAndVideos ===
    suspend fun migrateImagesAndVideos(
        context: Context,
        dao: RecipeElementDao,
        batchSize: Int = 50,
        progressListener: MigrationProgressListener? = null
    ) = withContext(Dispatchers.IO) {
        val documentsDir = context.filesDir
        var totalMigrated = 0

        while (true) {
            val elements = try {
                dao.fetchImageAndVideoElements(batchSize)
            } catch (e: Exception) {
                Log.e(TAG, "DB fetch error in migrateImagesAndVideos", e); break
            }
            if (elements.isEmpty()) break

            for (element in elements) {
                try {
                    val elementId = element.uniqueId ?: continue
                    when {
                        element.instructionType == "image" || element.type == "image" -> {
                            val rawImage = element.imageData ?: continue
                            val fileName = "${elementId}.jpg"
                            val fileUrl = File(documentsDir, fileName)
                            if (fileUrl.exists()) fileUrl.delete()
                            fileUrl.outputStream().use { it.write(rawImage) }
                            Log.d(TAG, "Wrote IMAGE for element $elementId at ${fileUrl.path}")

                            // ✅ Clear DB blob
                            element.imageData = null
                            dao.updateElement(element)
                        }

                        element.instructionType == "video" -> {
                            val rawVideo = element.attachmentData ?: continue
                            val fileExt = element.attachmentType ?: "mov"
                            val fileName = "${elementId}.$fileExt"
                            val fileUrl = File(documentsDir, fileName)
                            if (fileUrl.exists()) fileUrl.delete()
                            fileUrl.outputStream().use { it.write(rawVideo) }
                            Log.d(TAG, "Wrote VIDEO for element $elementId at ${fileUrl.path}")

                            // ✅ Clear video blob
                            element.attachmentData = null

                            // Preview image
                            element.imageData?.let { preview ->
                                val previewFile = File(documentsDir, "${elementId}.jpg")
                                if (previewFile.exists()) previewFile.delete()
                                previewFile.outputStream().use { it.write(preview) }
                                element.imageData = null
                                Log.d(TAG, "Wrote VIDEO preview for $elementId at ${previewFile.path}")
                            }

                            dao.updateElement(element)
                        }
                    }
                    totalMigrated++
                    withContext(Dispatchers.Main) {
                        progressListener?.onProgress(totalMigrated, "Instructions: $totalMigrated")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migrateImagesAndVideos for element ${element.uniqueId}", e)
                }
            }
        }

        Log.d(TAG, "Images/Videos migration completed. Total migrated: $totalMigrated.")
        withContext(Dispatchers.Main) {
            progressListener?.onProgress(totalMigrated, "Instructions: $totalMigrated")
        }
    }


    /**
     * migrateRecipePreviewImages: read recipe.image bytes, resize them (150px width) and store as jpg,
     * then clear the image field in DB (requires DAO update).
     *
     * NOTE: The Swift implementation resizes with UIKit. Here we do a simple pass-through write
     * (you can plug in an image resizing library like Bitmap scaling if you want parity).
     */
    suspend fun migrateRecipePreviewImages(
        context: Context,
        recipeDao: RecipeDao,
        batchSize: Int = 50,
        progressListener: MigrationProgressListener? = null
    ) = withContext(Dispatchers.IO) {
        val documentsDir = context.filesDir
        var totalMigrated = 0

        while (true) {
            val recipes = try {
                recipeDao.fetchRecipesWithImage(batchSize)
            } catch (e: Exception) {
                Log.e(TAG, "DB fetch error in migrateRecipePreviewImages", e)
                break
            }
            if (recipes.isEmpty()) break

            for (recipe in recipes) {
                try {
                    val recipeId = recipe.uniqueId
                    val imageData = recipe.image ?: continue
                    val fileName = "${recipeId}.jpg"
                    val fileUrl = File(documentsDir, fileName)
                    if (fileUrl.exists()) fileUrl.delete()
                    fileUrl.outputStream().use { it.write(imageData) }
                    Log.d(TAG, "Wrote preview for recipe $recipeId at ${fileUrl.path}")

                    // ✅ SAFE: Targeted SQL update - no full entity mutation
                    recipeDao.clearRecipeImage(recipeId)

                    totalMigrated++
                    withContext(Dispatchers.Main) {
                        progressListener?.onProgress(totalMigrated, "Recipe images: $totalMigrated")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in migrateRecipePreviewImages for recipe ${recipe.uniqueId}", e)
                }
            }
        }

        Log.d(TAG, "Recipe image migration completed. Total migrated: $totalMigrated.")
        withContext(Dispatchers.Main) {
            progressListener?.onProgress(totalMigrated, "Recipe images: $totalMigrated")
        }
    }
}