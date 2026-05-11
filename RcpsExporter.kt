package com.example.rezeptmoment.data

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RcpsExporter {

    suspend fun export(
        db: AppDatabase,
        filesDir: File,
        recipeIds: Set<UUID>,
        outputStream: OutputStream,
        onProgress: (String) -> Unit = {}
    ) {
        // --- Collect data
        val allRecipes = db.recipeDao().getAllRecipes().first()
        val selectedRecipes = allRecipes.filter { it.uniqueId in recipeIds }

        // categories that are actually referenced by the selected recipes
        val allCategories = db.recipeCategoryDao().getAllCategories().first()
        val neededCategoryIds = selectedRecipes.mapNotNull { it.belongsToCategoryId }.toSet()
        val selectedCategories = allCategories.filter { it.uniqueId in neededCategoryIds }

        // --- Build iOS-compatible JSON arrays (with strict ID guarding)

        // categories -> CategoryExport
        val categoriesJson = JSONArray().also { arr ->
            selectedCategories.forEach { c ->
                val id = c.uniqueId ?: return@forEach // skip if missing
                arr.put(
                    JSONObject()
                        .put("uniqueId", id.toString())
                        .put("primaryText", c.primaryText)
                        .put("orderingIndex", c.orderingIndex)
                )
            }
        }

        // recipes -> RecipeExport
        val recipesJson = JSONArray().also { arr ->
            selectedRecipes.forEach { r ->
                val id = r.uniqueId ?: return@forEach
                arr.put(
                    JSONObject()
                        .put("uniqueId", id.toString())
                        .put("belongsToCategoryId", r.belongsToCategoryId?.toString()) // nullable
                        .put("isPDFbasedRecipe", r.isPDFbasedRecipe == true)
                        .put("numberOfPersons", r.numberOfPersons ?: 1L)
                        .put("orderingIndex", r.orderingIndex)
                        .put("primaryText", r.primaryText ?: "")
                        .put("currentlySelectedNumberOfPersons", r.currentlySelectedNumberOfPersons ?: (r.numberOfPersons ?: 1L))
                )
            }
        }

        // steps -> RecipeStepExport
        val stepsJson = JSONArray()

        // --- Write ZIP (media first, then metadata.json)
        ZipOutputStream(outputStream).use { zos ->

            var processed = 0
            for (r in selectedRecipes) {
                processed++
                onProgress("$processed/${selectedRecipes.size}")

                val recipeId = r.uniqueId ?: continue

                // Optional recipe preview image <recipeId>.jpg
                val previewFile = File(filesDir, "$recipeId.jpg")
                if (previewFile.exists()) {
                    addFileToZip(zos, previewFile, previewFile.name)
                }

                // Elements for this recipe
                val elements = db.recipeElementDao().getElementsForRecipe(recipeId).first()
                elements.forEach { el ->
                    val elId = el.uniqueId
                    val rid = el.belongsToRecipeId
                    if (elId == null || rid == null) return@forEach

                    // Append JSON step row (parity with iOS struct)
                    stepsJson.put(
                        JSONObject()
                            .put("uniqueId", elId.toString())
                            .put("belongsToRecipeId", rid.toString())
                            .put("attachmentType", el.attachmentType)           // nullable
                            .put("instructionType", el.instructionType)         // nullable
                            .put("primaryText", el.primaryText)                 // nullable
                            .put("secondaryText", el.secondaryText)             // nullable
                            .put("quantity", el.quantity)                       // float
                            .put("type", el.type)                               // nullable
                            .put("orderingIndex", el.orderingIndex)
                            .put("numberOfCurrentPeople", el.numberOfCurrentPeople ?: 0L)
                    )

                    // Copy media alongside:
                    val ext = when {
                        el.type == "image" || el.instructionType == "image" -> "jpg"
                        el.instructionType == "video" -> (el.attachmentType ?: "mp4")
                        el.instructionType == "pdf" || el.type == "pdf" || el.instructionType == "importedPDF" -> "pdf"
                        else -> null
                    }

                    if (ext != null) {
                        val media = File(filesDir, "$elId.$ext")
                        if (media.exists()) addFileToZip(zos, media, media.name)
                    }

                    // Also include video preview JPG if present
                    if (el.instructionType == "video") {
                        val preview = File(filesDir, "$elId.jpg")
                        if (preview.exists()) addFileToZip(zos, preview, preview.name)
                    }
                }
            }

            // Finally write iOS-compatible metadata.json
            val root = JSONObject()
                .put("categories", categoriesJson)
                .put("recipes", recipesJson)
                .put("steps", stepsJson)

            addBytesToZip(zos, "metadata.json", root.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, nameInZip: String) {
        zos.putNextEntry(ZipEntry(nameInZip))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun addBytesToZip(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }
}
