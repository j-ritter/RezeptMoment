package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "DidUnlockPremium")
data class DidUnlockPremium(
    @PrimaryKey val id: Int = 1,
    val unlockedAt: Date? = null
)