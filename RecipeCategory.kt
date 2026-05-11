package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.*

@Entity(
    tableName = "RecipeCategory",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["email"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RecipeCategory(
    @PrimaryKey val uniqueId: UUID,
    val primaryText: String,
    val orderingIndex: Long,
    val userId: String
)