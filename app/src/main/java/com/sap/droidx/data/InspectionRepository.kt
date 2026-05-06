package com.sap.droidx.data

import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.BuildConfig
import com.sap.droidx.NetworkUtils
import com.sap.droidx.ui.InspectionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class InspectionRepository(
    private val http: OkHttpClient? = null,
    private val baseUrl: String? = null
) {
    private val client get() = http ?: ClientProvider.get()
    private val base   get() = baseUrl ?: "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"

    companion object {
        const val PAGE_SIZE = 50
    }

    suspend fun listInspections(skip: Int = 0, top: Int = PAGE_SIZE): List<InspectionRow> =
        withContext(Dispatchers.IO) {
            NetworkUtils.requireOnline()
            val select = "\$select=INSPECTION_ID,FORKLIFT_ID,OPERATOR_NAME,OPERATOR_ID," +
                    "INSPECTION_DATE,SHIFT,TIRE_PRESSURE,ODOMETER,PHYSICAL_CONDITION," +
                    "BRAKES,HYDRAULICS,HORN_LIGHTS,REMARKS,STATUS,CREATED_BY,CREATED_AT"
            val url = "$base/Inspections?\$top=$top&\$skip=$skip&\$orderby=CREATED_AT%20desc&$select"

            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")

            val arr = JSONObject(response.body?.string() ?: "{}").getJSONArray("value")
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { o ->
                    InspectionRow(
                        inspectionId      = o.optString("INSPECTION_ID"),
                        forkliftId        = o.optString("FORKLIFT_ID"),
                        operator          = o.optString("OPERATOR_NAME"),
                        operatorId        = o.optString("OPERATOR_ID"),
                        status            = o.optString("STATUS"),
                        date              = o.optString("INSPECTION_DATE"),
                        shift             = o.optString("SHIFT"),
                        tirePressure      = o.optString("TIRE_PRESSURE"),
                        odometer          = o.optInt("ODOMETER", 0),
                        physicalCondition = o.optString("PHYSICAL_CONDITION"),
                        brakes            = o.optString("BRAKES"),
                        hydraulics        = o.optString("HYDRAULICS"),
                        hornLights        = o.optString("HORN_LIGHTS"),
                        remarks           = o.optString("REMARKS"),
                        createdBy         = o.optString("CREATED_BY"),
                        createdAt         = o.optString("CREATED_AT")
                    )
                }
            }
        }

    suspend fun getInspectionPhoto(id: String): String? = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val url = "$base/Inspections('$id')?\$select=PHOTO_DATA"
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) return@withContext null
        val value = JSONObject(response.body?.string() ?: "{}").opt("PHOTO_DATA")
        if (value == null || value == JSONObject.NULL || value.toString() == "null") null
        else value.toString().takeIf { it.isNotBlank() }
    }

    suspend fun deleteInspection(id: String) = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val url = "$base/Inspections('$id')"
        val response = client.newCall(Request.Builder().url(url).delete().build()).execute()
        if (!response.isSuccessful)
            throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()}")
    }
}
