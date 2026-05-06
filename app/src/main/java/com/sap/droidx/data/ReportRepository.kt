package com.sap.droidx.data

import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ReportRepository {

    private val http by lazy { ClientProvider.get() }
    private val base get() = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun createReport(
        operator: String,
        site: String,
        location: String,
        description: String,
        urgency: String,
        photoBase64: String? = null
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("OPERATOR", operator)
            put("SITE", site)
            put("LOCATION", location)
            put("DESCRIPTION", description)
            put("URGENCY", urgency)
            if (photoBase64 != null) put("PHOTO_DATA", photoBase64)
        }
        val resp = http.newCall(
            Request.Builder().url("$base/Reports")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
    }
}
