package com.example.rezeptmoment.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * RCPS importer with optional progress updates.
 *
 * Expected ZIP layout (flexible):
 * - metadata.json (preferred) or manifest.json or recipes.json
 *   {
 *     "categories": [{ "uniqueId":"...", "primaryText":"...", "orderingIndex":1 }],
 *     "recipes": [{
 *        "uniqueId":"...", "primaryText":"...", "belongsToCategoryId":"...", "orderingIndex":1,
 *        "isMarked":false, "dateOfMarking": 1700000000000
 *     }],
 *     // Elements array can be named "steps" (new) or "elements" (old)
 *     "steps": [{
 *        "uniqueId":"...",
 *        "belongsToRecipeId":"...",
 *        "orderingIndex":1,
 *        "type":"image|ingredients|steps",
 *        "instructionType":"text|image|video|link|pdf|importedPDF",
 *        "primaryText":"string",            // may be absent in older zips
 *        "secondaryText":"string",          // optional
 *        "quantity": 0.0,                   // optional
 *        "attachmentType":"mp4|mov|pdf|jpg" // optional
 *        "numberOfCurrentPeople": 0         // optional
 *     }]
 *   }
 * - Optional media files named by UUID with extension:
 *   <elementId>.jpg / .pdf / .mp4 etc
 *   <recipeId>.jpg (recipe preview)
 */
object RcpsImporter {

    data class Result(
        val categories: Int,
        val recipes: Int,
        val elements: Int,
        val mediaFiles: Int
    )

    suspend fun import(
        bytes: ByteArray,
        db: AppDatabase,
        filesDir: File,
        onProgress: ((String) -> Unit)? = null,
        userId: String
    ): Result {
        onProgress?.invoke("Reading archive…")

        // 1) Read ZIP entries to memory map
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var ze: ZipEntry? = zis.nextEntry
            var fileCount = 0
            while (ze != null) {
                if (!ze.isDirectory) {
                    val buf = zis.readBytes()
                    entries[ze.name] = buf
                    fileCount++
                    if (fileCount % 10 == 0) onProgress?.invoke("Reading archive… $fileCount files")
                }
                zis.closeEntry()
                ze = zis.nextEntry
            }
        }

        onProgress?.invoke("Parsing manifest…")

        // 2) Parse manifest: prefer metadata.json, else manifest.json, else recipes.json
        val manifestBytes = entries["metadata.json"]
            ?: entries["manifest.json"]
            ?: entries["recipes.json"]
            ?: throw IllegalArgumentException("RCPS archive missing metadata.json / manifest.json / recipes.json")

        val root = JSONObject(String(manifestBytes))

        val categoriesArr = root.optJSONArray("categories") ?: JSONArray()
        val recipesArr    = root.optJSONArray("recipes")    ?: JSONArray()

        // Elements array may be called "steps" (new) or "elements" (legacy)
        val elementsArr   = root.optJSONArray("steps")
            ?: root.optJSONArray("elements")
            ?: JSONArray()

        var mediaCopied = 0

        // Simple progress targets
        val totalSteps =
            (if (categoriesArr.length() > 0) 1 else 0) +
                    (if (recipesArr.length() > 0) 1 else 0) +
                    (if (elementsArr.length() > 0) 1 else 0)
        var step = 0

        // 3) Insert everything in a single transaction and copy media to filesDir
        db.withTransaction {
            // ---- categories ----
            if (categoriesArr.length() > 0) onProgress?.invoke("Importing categories…")
            for (i in 0 until categoriesArr.length()) {
                val j = categoriesArr.getJSONObject(i)
                val cat = RecipeCategory(
                    uniqueId = j.optStringUUID("uniqueId") ?: UUID.randomUUID(),
                    primaryText = j.optString("primaryText", "Category"),
                    orderingIndex = j.optLong("orderingIndex", 1L),
                    userId = userId
                )
                db.recipeCategoryDao().insertCategory(cat)
                if (i % 25 == 0) onProgress?.invoke("Importing categories… ${i + 1}/${categoriesArr.length()}")
            }
            if (categoriesArr.length() > 0) {
                step++
                onProgress?.invoke("Categories done ($step/$totalSteps)")
            }

            // ---- recipes ----
            if (recipesArr.length() > 0) onProgress?.invoke("Importing recipes…")
            for (i in 0 until recipesArr.length()) {
                val j = recipesArr.getJSONObject(i)

                // Be tolerant to missing optional fields
                val recipe = Recipe(
                    uniqueId             = j.optStringUUID("uniqueId") ?: UUID.randomUUID(),
                    primaryText          = j.optStringOrNull("primaryText"),
                    belongsToCategoryId  = j.optStringUUID("belongsToCategoryId"),
                    orderingIndex        = j.optLong("orderingIndex", System.currentTimeMillis()),
                    isMarked             = j.optBoolean("isMarked", false),
                    dateOfMarking        = j.optLongDateOrNull("dateOfMarking"),
                    image                = null,
                    userId               = userId
                )
                db.recipeDao().insertRecipe(recipe)

                // Optional preview image: <recipeId>.jpg
                val previewName = "${recipe.uniqueId}.jpg"
                entries[previewName]?.let { data ->
                    File(filesDir, previewName).writeBytes(data)
                    mediaCopied++
                }

                if (i % 25 == 0) onProgress?.invoke("Importing recipes… ${i + 1}/${recipesArr.length()}")
            }
            if (recipesArr.length() > 0) {
                step++
                onProgress?.invoke("Recipes done ($step/$totalSteps)")
            }

            // ---- elements / steps ----
            if (elementsArr.length() > 0) onProgress?.invoke("Importing items…")
            for (i in 0 until elementsArr.length()) {
                val j = elementsArr.getJSONObject(i)

                val elId      = j.optStringUUID("uniqueId") ?: UUID.randomUUID()
                val belongsTo = j.optStringUUID("belongsToRecipeId") ?: continue

                val instrType = j.optStringOrNull("instructionType")

                // ✅ REQUIRED non-null Room fields with sensible defaults
                val resolvedType: String = j.optStringOrNull("type") ?: "steps"
                val primary: String = j.optStringOrNull("primaryText")
                    ?: j.optStringOrNull("instructionText")
                    ?: ""

                val el = RecipeElement(
                    uniqueId              = elId,
                    belongsToRecipeId     = belongsTo,
                    type                  = resolvedType,                                // non-null
                    instructionType       = instrType,                                   // nullable
                    primaryText           = primary,                                     // non-null
                    instructionText       = j.optStringOrNull("instructionText"),
                    orderingIndex         = j.optLong("orderingIndex", 1L),
                    attachmentData        = null,
                    attachmentType        = j.optStringOrNull("attachmentType"),
                    imageData             = null,
                    quantity              = j.optDoubleOrNull("quantity")?.toFloat() ?: 0f,
                    secondaryText         = j.optStringOrNull("secondaryText"),
                    numberOfCurrentPeople = j.optLongOrNull("numberOfCurrentPeople")
                )
                db.recipeElementDao().insertElement(el)

                // Copy media by guessing filename <elementId>.<ext>
                val guessedExt = when {
                    el.type == "image" || el.instructionType == "image" -> "jpg"
                    el.instructionType == "video" -> el.attachmentType ?: "mp4"
                    el.instructionType == "pdf" || el.type == "pdf" || el.instructionType == "importedPDF" -> "pdf"
                    else -> null
                }

                if (guessedExt != null) {
                    val mediaName = "${el.uniqueId}.$guessedExt"
                    entries[mediaName]?.let { data ->
                        File(filesDir, mediaName).writeBytes(data)
                        mediaCopied++
                    }
                }

                // For videos, optional preview <id>.jpg
                if (el.instructionType == "video") {
                    val previewName = "${el.uniqueId}.jpg"
                    entries[previewName]?.let { preview ->
                        File(filesDir, previewName).writeBytes(preview)
                        mediaCopied++
                    }
                }

                if (i % 50 == 0) onProgress?.invoke("Importing items… ${i + 1}/${elementsArr.length()}")
            }
            if (elementsArr.length() > 0) {
                step++
                onProgress?.invoke("Items done ($step/$totalSteps)")
            }
        }

        onProgress?.invoke("Finalizing…")

        return Result(
            categories = categoriesArr.length(),
            recipes = recipesArr.length(),
            elements = elementsArr.length(),
            mediaFiles = mediaCopied
        ).also {
            onProgress?.invoke("Import complete: ${it.recipes} recipes, ${it.elements} items")
        }
    }

    // --- JSON helpers (kept local/private) ---

    private fun JSONObject.optStringUUID(key: String): UUID? {
        val s = optString(key, "")
        return if (s.isNotBlank()) runCatching { UUID.fromString(s) }.getOrNull() else null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optLongDateOrNull(key: String): java.util.Date? {
        if (!has(key) || isNull(key)) return null
        val millis = optLong(key, Long.MIN_VALUE)
        return if (millis == Long.MIN_VALUE) null else java.util.Date(millis)
    }
}
