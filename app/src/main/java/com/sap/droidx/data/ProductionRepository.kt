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

class ProductionRepository(
    private val http: OkHttpClient? = null,
    private val baseUrl: String? = null
) {
    private val client    get() = http ?: ClientProvider.get()
    private val base      get() = baseUrl ?: "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
    private val jsonMedia = "application/json".toMediaType()

    suspend fun fetchFirstOpenOrder(): ProductionOrder? = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val resp = client.newCall(
            Request.Builder().url("$base/ProductionOrders").get().build()
        ).execute()
        if (!resp.isSuccessful) return@withContext null
        val arr = JSONObject(resp.body!!.string()).optJSONArray("value") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            if (j.optString("STATUS") == "OPEN") {
                return@withContext ProductionOrder(
                    orderId      = j.optString("ORDER_ID"),
                    material     = j.optString("MATERIAL"),
                    materialDesc = j.optString("MATERIAL_DESC"),
                    plannedQty   = j.optInt("PLANNED_QTY", 0),
                    receivedQty  = j.optInt("RECEIVED_QTY", 0),
                    batch        = j.optString("BATCH"),
                    palletQty    = j.optInt("PALLET_QTY", 1),
                    status       = j.optString("STATUS"),
                    door         = j.optString("DOOR"),
                    shipTo       = j.optString("SHIP_TO")
                )
            }
        }
        null
    }

    suspend fun fetchOrder(orderId: String): ProductionOrder = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val resp = client.newCall(
            Request.Builder().url("$base/ProductionOrders('${orderId.trim()}')").get().build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("Order not found (HTTP ${resp.code})")
        val j = JSONObject(resp.body!!.string())
        ProductionOrder(
            orderId      = j.optString("ORDER_ID"),
            material     = j.optString("MATERIAL"),
            materialDesc = j.optString("MATERIAL_DESC"),
            plannedQty   = j.optInt("PLANNED_QTY", 0),
            receivedQty  = j.optInt("RECEIVED_QTY", 0),
            batch        = j.optString("BATCH"),
            palletQty    = j.optInt("PALLET_QTY", 1),
            status       = j.optString("STATUS"),
            door         = j.optString("DOOR"),
            shipTo       = j.optString("SHIP_TO")
        )
    }

    suspend fun createGR(orderId: String, qty: Int, grBy: String): GrResult =
        withContext(Dispatchers.IO) {
            NetworkUtils.requireOnline()
            val payload = JSONObject().apply {
                put("ORDER_ID", orderId)
                put("QTY", qty)
                put("GR_BY", grBy)
            }
            val resp = client.newCall(
                Request.Builder().url("$base/GrItems")
                    .post(payload.toString().toRequestBody(jsonMedia)).build()
            ).execute()
            if (!resp.isSuccessful) throw IllegalStateException("GR failed (HTTP ${resp.code}): ${resp.body?.string()}")
            val j = JSONObject(resp.body!!.string())
            GrResult(grId = j.optString("GR_ID"), huId = j.optString("HU_ID"), newReceivedQty = 0)
        }

    suspend fun createDelivery(
        orderId: String, door: String, shipTo: String, huCount: Int, createdBy: String
    ): String = withContext(Dispatchers.IO) {
        NetworkUtils.requireOnline()
        val deliveryNumber = "GR${(System.currentTimeMillis() % 100000000L).toString().padStart(8, '0')}"
        val comments = "Production GR | Order: $orderId | Door: $door | Ship To: $shipTo | Pallets: $huCount"
        val payload = JSONObject().apply {
            put("DELIVERY_NUMBER", deliveryNumber)
            put("COMMENTS", comments)
            put("CREATED_BY", createdBy)
        }
        val resp = client.newCall(
            Request.Builder().url("$base/Deliveries")
                .post(payload.toString().toRequestBody(jsonMedia)).build()
        ).execute()
        if (!resp.isSuccessful) throw IllegalStateException("Delivery failed (HTTP ${resp.code}): ${resp.body?.string()}")
        JSONObject(resp.body!!.string()).optString("DELIVERY_ID", deliveryNumber)
    }
}
