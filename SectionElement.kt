package com.example.rezeptmoment.ui.theme

import java.util.UUID

data class SectionElement(
    val id: UUID = UUID.randomUUID(),
    val derivedFromCoreDataObjectId: UUID? = null,
    val isUndefinedSection: Boolean,
    val title: String?,
    val isMarked: Boolean,
    val orderingIndex: Long
)