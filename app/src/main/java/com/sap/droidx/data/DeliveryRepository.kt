package com.sap.droidx.data

import android.util.Log
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.ui.DeliveryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DeliveryRepository {

    private val http by lazy { ClientProvider.get() }
    private val base get() = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun listDeliveries(): List<DeliveryRow> = withContext(Dispatchers.IO) {
        val url = "$base/Deliveries?\$top=100&\$orderby=CREATED_AT%20desc"
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
        val arr = JSONObject(resp.body?.string() ?: "{}").getJSONArray("value")
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).toDeliveryRow()
        }
    }

    suspend fun getDelivery(id: String): DeliveryRow? = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url("$base/Deliveries('$id')").build()).execute()
        if (!resp.isSuccessful) return@withContext null
        JSONObject(resp.body?.string() ?: "{}").toDeliveryRow()
    }

    suspend fun getDeliveryPhotos(deliveryId: String): List<DeliveryPhoto> = withContext(Dispatchers.IO) {
        val url = "$base/DeliveryPhotos?\$filter=DELIVERY_ID%20eq%20'$deliveryId'&\$orderby=SEQ%20asc"
        Log.d(TAG, "getDeliveryPhotos url=$url")
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) return@withContext emptyList()
        val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("value") ?: JSONArray()
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { o ->
                DeliveryPhoto(
                    photoId    = o.optString("PHOTO_ID"),
                    deliveryId = o.optString("DELIVERY_ID"),
                    photoData  = o.opt("PHOTO_DATA").let { v ->
                        if (v == null || v == JSONObject.NULL) null else v.toString().takeIf { it.isNotBlank() }
                    },
                    seq        = o.optInt("SEQ", 0)
                )
            }
        }
    }

    suspend fun createDelivery(number: String, comments: String, createdBy: String): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("DELIVERY_NUMBER", number)
                put("COMMENTS", comments)
                put("CREATED_BY", createdBy)
            }
            val resp = http.newCall(
                Request.Builder().url("$base/Deliveries")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
            JSONObject(resp.body?.string() ?: "{}").optString("DELIVERY_ID")
        }

    suspend fun updateDelivery(id: String, number: String, comments: String) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("DELIVERY_NUMBER", number)
                put("COMMENTS", comments)
            }
            val resp = http.newCall(
                Request.Builder().url("$base/Deliveries('$id')")
                    .method("PATCH", payload.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
        }

    suspend fun deleteDelivery(id: String) = withContext(Dispatchers.IO) {
        val resp = http.newCall(Request.Builder().url("$base/Deliveries('$id')").delete().build()).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
    }

    suspend fun addPhoto(deliveryId: String, photoBase64: String, seq: Int): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("DELIVERY_ID", deliveryId)
                put("PHOTO_DATA", photoBase64)
                put("SEQ", seq)
            }
            val resp = http.newCall(
                Request.Builder().url("$base/DeliveryPhotos")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
            JSONObject(resp.body?.string() ?: "{}").optString("PHOTO_ID")
        }

    suspend fun deletePhoto(photoId: String) = withContext(Dispatchers.IO) {
        val resp = http.newCall(
            Request.Builder().url("$base/DeliveryPhotos('$photoId')").delete().build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string()}")
    }

    private fun JSONObject.toDeliveryRow() = DeliveryRow(
        deliveryId     = optString("DELIVERY_ID"),
        deliveryNumber = optString("DELIVERY_NUMBER"),
        comments       = opt("COMMENTS").let { v -> if (v == null || v == JSONObject.NULL) "" else v.toString() },
        createdBy      = optString("CREATED_BY"),
        createdAt      = optString("CREATED_AT"),
        updatedAt      = optString("UPDATED_AT")
    )

    companion object {
        private const val TAG = "DeliveryRepo"
    }
}

data class DeliveryPhoto(
    val photoId: String,
    val deliveryId: String,
    val photoData: String?,
    val seq: Int
)
