package com.nmc.stocktake.data

data class Medication(
    val name: String,
    val onSystem: Int,
    var physicalCount: Int = 0,
    var expiry: String = "",
    var batch: String = "",
    var note: String = "",
    var photoPath: String? = null
)
