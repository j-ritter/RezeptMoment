package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

@Entity(
    tableName = "Recipe",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["email"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE  // Delete recipes if user deleted
        )
    ]
)
data class Recipe(
    @PrimaryKey val uniqueId: UUID,
    val primaryText: String?,
    val belongsToCategoryId: UUID?, // null if uncategorized
    val orderingIndex: Long,
    val isMarked: Boolean,
    val dateOfMarking: Date?,
    var image: ByteArray? = null, // For migration purposes
    val isPDFbasedRecipe: Boolean = false,
    val currentlySelectedNumberOfPersons: Long? = null,
    var numberOfPersons: Long = 1L,
    var shoppingItemCount: Int? = null,
    val userId: String  // References User.email
) {
    @Ignore
    var hasShoppingItems: Boolean = false // Not persisted by Room
}