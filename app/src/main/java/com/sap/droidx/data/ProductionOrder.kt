package com.sap.droidx.data

data class ProductionOrder(
    val orderId:      String,
    val material:     String,
    val materialDesc: String,
    val plannedQty:   Int,
    val receivedQty:  Int,
    val batch:        String,
    val palletQty:    Int,
    val status:       String,
    val door:         String,
    val shipTo:       String
)

data class GrResult(
    val grId:           String,
    val huId:           String,
    val newReceivedQty: Int
)
