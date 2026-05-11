package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "UpcomingObject")
data class UpcomingObject(
    @PrimaryKey val uniqueId: UUID = UUID.randomUUID(),
var isMarkedAsComplete: Boolean = false,
var belongsToRecipeId: UUID? = null,
var belongsToObjectId: UUID? = null,
val derivedFromCoreDataObjectId: UUID? = null,
var primaryText: String? = null,
var secondaryText: String? = null,
var quantity: Float = 0f,
var orderingIndex: Long = 0,
var type: String = "",
var date: java.util.Date? = null,
var linkedIngredientId: UUID? = null
)
