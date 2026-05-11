package com.example.rezeptmoment.ui.theme

import androidx.room.TypeConverter
import java.util.Date
import java.util.UUID

class Converters {
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun toUUID(uuid: String?): UUID? = uuid?.let { UUID.fromString(it) }

    @TypeConverter
    fun fromDate(date: java.util.Date?): Long? = date?.time

    @TypeConverter
    fun toDate(timestamp: Long?): java.util.Date? = timestamp?.let { java.util.Date(it) }

}
