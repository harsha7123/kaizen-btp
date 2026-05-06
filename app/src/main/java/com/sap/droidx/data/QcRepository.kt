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
import org.json.JSONObject

class QcRepository(
    private val http: OkHttpClient? = null,
    private val baseUrl: String? = null
) {
    private val client    get() = http ?: ClientProvider.get()
    private val base      get() = baseUrl ?: "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun fetchItem(sourceId: String, sourceType: String): QcItem =
        withContext(Dispatchers.IO) {
            NetworkUtils.requireOnline()
            val url = "$base/QcItems?\$filter=SOURCE_ID eq '$sourceId' and SOURCE_TYPE eq '$sourceType'&\$top=1"
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            if (!resp.isSuccessful) throw IllegalStateException("Item not found (HTTP ${resp.code})")
            val root = JSONObject(resp.body!!.string())
            val arr  = root.optJSONArray("value")
                ?: throw IllegalStateException("Unexpected response format")
            if (arr.length() == 0) throw IllegalStateException("No QC record found for $sourceId")
            val j = arr.getJSONObject(0)
            QcItem(
                itemId         = j.optString("ITEM_ID"),
                sourceId       = j.optString("SOURCE_ID"),
                sourceType     = j.optString("SOURCE_TYPE"),
                material       = j.optString("MATERIAL"),
                materialDesc   = j.optString("MATERIAL_DESC"),
                qty            = j.optInt("QTY", 0),
                supplier       = j.optString("SUPPLIER"),
                batch          = j.optString("BATCH"),
                expirationDate = j.optString("EXPIRATION_DATE"),
                status         = j.optString("STATUS", "PENDING")
            )
        }

    suspend fun updateStatus(itemId: String, status: String, qcBy: String) =
        withContext(Dispatchers.IO) {
            NetworkUtils.requireOnline()
            val payload = JSONObject().apply {
                put("STATUS", status)
                put("QC_BY", qcBy)
            }
            val resp = client.newCall(
                Request.Builder().url("$base/QcItems('$itemId')")
                    .patch(payload.toString().toRequestBody(jsonMedia)).build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("Update failed (HTTP ${resp.code}): ${resp.body?.string()}")
        }
}
