package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable
import java.util.UUID

@Entity(tableName = "RecipeElement")
data class RecipeElement(
    @PrimaryKey val uniqueId: UUID,
    val belongsToRecipeId: UUID,

    // type group ("image" | "ingredients" | "steps")
    var type: String,

    // detailed instruction subtype for steps ("text" | "image" | "video" | "link" | "pdf" (legacy) | "importedPDF" (legacy))
    var instructionType: String?,

    // For ingredients: UNIT (e.g., "cup", "ml"). For links/text: main string.
    var primaryText: String,

    // For links: optional display name. For steps text: the text body if you prefer.
    var instructionText: String?,

    // Unified ordering across section
    var orderingIndex: Long,

    // Media blobs (kept null; we use files)
    var attachmentData: ByteArray?,
    var attachmentType: String?,
    var imageData: ByteArray?,

    // quantity (Float, default 0), ingredient name (secondaryText), and current people override
    var quantity: Float = 0f,
    var secondaryText: String? = null,
    var numberOfCurrentPeople: Long?
) : Serializable
