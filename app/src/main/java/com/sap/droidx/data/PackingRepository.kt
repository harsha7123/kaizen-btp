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

class PackingRepository(
    private val http: OkHttpClient? = null,
    private val baseUrl: String? = null
) {
    private val client    get() = http ?: ClientProvider.get()
    private val base      get() = baseUrl ?: "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun createHU(huType: String, createdBy: String): String = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val payload = JSONObject().apply {
            put("HU_TYPE", huType)
            put("CREATED_BY", createdBy)
        }
        val resp = client.newCall(
            Request.Builder().url("$base/HandlingUnits")
                .post(payload.toString().toRequestBody(jsonMedia)).build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
        JSONObject(resp.body!!.string()).getString("HU_ID")
    }

    suspend fun packItem(
        destHuId: String, sourceType: String, sourceId: String, qty: Int, packedBy: String
    ) = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val payload = JSONObject().apply {
            put("DEST_HU_ID", destHuId)
            put("SOURCE_TYPE", sourceType)
            put("SOURCE_ID", sourceId)
            put("QTY", qty)
            put("PACKED_BY", packedBy)
        }
        val resp = client.newCall(
            Request.Builder().url("$base/PackingItems")
                .post(payload.toString().toRequestBody(jsonMedia)).build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
    }
}
