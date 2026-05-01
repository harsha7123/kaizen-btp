package com.sap.droidx.data

import android.util.Log
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.ui.InspectionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class InspectionRepository {

    private val http by lazy { ClientProvider.get() }

    suspend fun listInspections(): List<InspectionRow> = withContext(Dispatchers.IO) {
        val select = "\$select=INSPECTION_ID,FORKLIFT_ID,OPERATOR_NAME,OPERATOR_ID," +
                "INSPECTION_DATE,SHIFT,TIRE_PRESSURE,ODOMETER,PHYSICAL_CONDITION," +
                "BRAKES,HYDRAULICS,HORN_LIGHTS,REMARKS,STATUS,CREATED_BY,CREATED_AT"
        val url = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}" +
                "/Inspections?\$top=100&\$orderby=CREATED_AT%20desc&$select"
        Log.d(TAG, "Fetching: $url")

        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")

        val body = response.body?.string() ?: "{}"
        val arr = JSONObject(body).getJSONArray("value")
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                InspectionRow(
                    inspectionId     = o.optString("INSPECTION_ID"),
                    forkliftId       = o.optString("FORKLIFT_ID"),
                    operator         = o.optString("OPERATOR_NAME"),
                    operatorId       = o.optString("OPERATOR_ID"),
                    status           = o.optString("STATUS"),
                    date             = o.optString("INSPECTION_DATE"),
                    shift            = o.optString("SHIFT"),
                    tirePressure     = o.optString("TIRE_PRESSURE"),
                    odometer         = o.optInt("ODOMETER", 0),
                    physicalCondition= o.optString("PHYSICAL_CONDITION"),
                    brakes           = o.optString("BRAKES"),
                    hydraulics       = o.optString("HYDRAULICS"),
                    hornLights       = o.optString("HORN_LIGHTS"),
                    remarks          = o.optString("REMARKS"),
                    createdBy        = o.optString("CREATED_BY"),
                    createdAt        = o.optString("CREATED_AT")
                )
            }
        }.also { Log.d(TAG, "Parsed ${it.size} rows") }
    }

    suspend fun getInspectionPhoto(id: String): String? = withContext(Dispatchers.IO) {
        val url = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}" +
                "/Inspections('$id')?\$select=PHOTO_DATA"
        Log.d(TAG, "getInspectionPhoto url=$url")
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        Log.d(TAG, "getInspectionPhoto response code=${response.code}")
        if (!response.isSuccessful) return@withContext null
        val body = response.body?.string() ?: "{}"
        Log.d(TAG, "getInspectionPhoto body prefix=${body.take(120)}")
        val value = JSONObject(body).opt("PHOTO_DATA")
        if (value == null || value == JSONObject.NULL || value.toString() == "null") null
        else value.toString().takeIf { it.isNotBlank() }
    }

    suspend fun deleteInspection(id: String) = withContext(Dispatchers.IO) {
        val url = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}" +
                "/Inspections('$id')"
        val response = http.newCall(Request.Builder().url(url).delete().build()).execute()
        Log.d(TAG, "deleteInspection response code=${response.code}")
        if (!response.isSuccessful)
            throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()}")
    }

    companion object {
        private const val TAG = "InspectionRepo"
    }
}
