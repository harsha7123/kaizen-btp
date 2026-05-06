package com.sap.droidx.data

data class QcItem(
    val itemId:         String,
    val sourceId:       String,
    val sourceType:     String,
    val material:       String,
    val materialDesc:   String,
    val qty:            Int,
    val supplier:       String,
    val batch:          String,
    val expirationDate: String,
    val status:         String
)
