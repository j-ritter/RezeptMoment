package com.example.rezeptmoment.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey val email: String,
    val passwordHash: String,  // Store hashed password (not plain text!)
    val createdAt: Long = System.currentTimeMillis()
)