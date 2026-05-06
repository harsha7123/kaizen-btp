package com.sap.droidx.data

import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ConfirmTaskRepository(
    private val http: OkHttpClient? = null,
    private val baseUrl: String? = null
) {
    private val client    get() = http ?: ClientProvider.get()
    private val base      get() = baseUrl ?: "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun fetchTask(taskNumber: String): WarehouseTask =
        withContext(Dispatchers.IO) {
            NetworkUtils.requireOnline()
            val url = "$base/WarehouseTasks?\$filter=TASK_NUMBER eq '$taskNumber'&\$top=1"
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching task")
            val root = JSONObject(resp.body!!.string())
            val arr  = root.optJSONArray("value")
                ?: throw IllegalStateException("Unexpected response format")
            if (arr.length() == 0) throw IllegalStateException("No warehouse task found for: $taskNumber")
            val j = arr.getJSONObject(0)
            fun str(key: String): String {
                val v = j.opt(key)
                return if (v == null || v == JSONObject.NULL) "" else v.toString()
            }
            WarehouseTask(
                taskId       = str("TASK_ID"),
                taskNumber   = str("TASK_NUMBER"),
                hu           = str("HU"),
                material     = str("MATERIAL"),
                materialDesc = str("MATERIAL_DESC"),
                qty          = j.optDouble("QTY", 0.0),
                uom          = str("UOM"),
                sourceBin    = str("SOURCE_BIN"),
                destBin      = str("DEST_BIN"),
                status       = str("STATUS").ifEmpty { "OPEN" },
                confirmedHu  = str("CONFIRMED_HU"),
                confirmedQty = j.optDouble("CONFIRMED_QTY", 0.0),
                confirmedBin = str("CONFIRMED_BIN"),
                confirmedBy  = str("CONFIRMED_BY"),
                confirmedAt  = str("CONFIRMED_AT"),
                physicalAddress = str("PHYSICAL_ADDRESS"),
                city         = str("CITY")
            )
        }

    suspend fun confirmTask(
        taskId: String,
        confirmedHu: String,
        confirmedQty: Double,
        bin: String,
        lat: Double?,
        lon: Double?,
        accuracy: Float?,
        operatorName: String,
        photos: List<String>,
        physicalAddress: String,
        city: String
    ) = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val payload = JSONObject().apply {
            put("STATUS",            "CONFIRMED")
            put("CONFIRMED_HU",      confirmedHu)
            put("CONFIRMED_QTY",     confirmedQty)
            put("CONFIRMED_BIN",     bin)
            put("CONFIRMED_BY",      operatorName)
            if (lat != null)      put("LATITUDE",          lat)
            if (lon != null)      put("LONGITUDE",         lon)
            if (accuracy != null) put("LOCATION_ACCURACY", accuracy.toDouble())
            if (physicalAddress.isNotEmpty()) put("PHYSICAL_ADDRESS", physicalAddress)
            if (city.isNotEmpty())            put("CITY",             city)
            if (photos.isNotEmpty()) put("PHOTO",           JSONArray(photos).toString())
        }
        val resp = client.newCall(
            Request.Builder()
                .url("$base/WarehouseTasks('$taskId')")
                .patch(payload.toString().toRequestBody(jsonMedia))
                .build()
        ).execute()
        if (!resp.isSuccessful) {
            val body = resp.body?.string() ?: ""
            throw IllegalStateException("Confirm failed (HTTP ${resp.code}): $body")
        }
    }
}
