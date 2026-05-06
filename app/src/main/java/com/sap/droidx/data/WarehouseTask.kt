package com.sap.droidx.data

data class WarehouseTask(
    val taskId: String,
    val taskNumber: String,
    val hu: String,
    val material: String,
    val materialDesc: String,
    val qty: Double,
    val uom: String,
    val sourceBin: String,
    val destBin: String,
    val status: String,
    val confirmedHu: String = "",
    val confirmedQty: Double = 0.0,
    val confirmedBin: String = "",
    val confirmedBy: String = "",
    val confirmedAt: String = "",
    val physicalAddress: String = "",
    val city: String = ""
)
