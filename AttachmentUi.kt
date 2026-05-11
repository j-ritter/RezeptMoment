package com.example.rezeptmoment.data

data class AttachmentUi(
    val type: AttachmentType,
    var label: String,
    var data: String // For links: URL, for PDFs: URI, for scans: file label/path
)
enum class AttachmentType { LINK, PDF, SCAN_PAGE }

