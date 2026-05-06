package com.sap.droidx.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QcRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: QcRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = QcRepository(
            http    = OkHttpClient(),
            baseUrl = server.url("/api").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun odataListResponse(item: String) =
        """{"@odata.context":"${'$'}metadata#QcItems","value":[$item]}"""

    @Test
    fun `fetchItem parses QC item from OData envelope`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(odataListResponse("""
                {
                  "ITEM_ID":"qc-uuid-001","SOURCE_ID":"MAT-A001","SOURCE_TYPE":"MATERIAL",
                  "MATERIAL":"MAT-A001","MATERIAL_DESC":"Widget Assembly Type A",
                  "QTY":100,"SUPPLIER":"ACME Supplies","BATCH":"BATCH-2026-001",
                  "EXPIRATION_DATE":"2027-06-30","STATUS":"PENDING"
                }
            """.trimIndent()))
        )

        val item = repo.fetchItem("MAT-A001", "MATERIAL")

        assertEquals("qc-uuid-001",             item.itemId)
        assertEquals("MAT-A001",                item.sourceId)
        assertEquals("MATERIAL",                item.sourceType)
        assertEquals("Widget Assembly Type A",  item.materialDesc)
        assertEquals(100,                       item.qty)
        assertEquals("ACME Supplies",           item.supplier)
        assertEquals("PENDING",                 item.status)
    }

    @Test
    fun `fetchItem throws when value array is empty`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"value":[]}""")
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            runTest { repo.fetchItem("UNKNOWN", "MATERIAL") }
        }
        assertTrue(ex.message?.contains("No QC record") == true)
    }

    @Test
    fun `fetchItem throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThrows(IllegalStateException::class.java) {
            runTest { repo.fetchItem("MAT-A001", "MATERIAL") }
        }
    }

    @Test
    fun `updateStatus sends correct PATCH payload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        repo.updateStatus("qc-uuid-001", "APPROVED", "J.Martinez")

        val recorded = server.takeRequest()
        assertEquals("PATCH",  recorded.method)
        assertTrue(recorded.path?.contains("qc-uuid-001") == true)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("APPROVED"))
        assertTrue(body.contains("J.Martinez"))
    }

    @Test
    fun `updateStatus throws on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        assertThrows(IllegalStateException::class.java) {
            runTest { repo.updateStatus("qc-uuid-001", "APPROVED", "J.Martinez") }
        }
    }
}
