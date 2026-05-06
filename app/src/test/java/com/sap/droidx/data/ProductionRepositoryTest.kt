package com.sap.droidx.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProductionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ProductionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = ProductionRepository(
            http    = OkHttpClient(),
            baseUrl = server.url("/api").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `fetchOrder parses production order correctly`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                  "ORDER_ID":"PRD-001","MATERIAL":"MAT-A001",
                  "MATERIAL_DESC":"Widget Assembly","PLANNED_QTY":100,
                  "RECEIVED_QTY":20,"BATCH":"BATCH-001","PALLET_QTY":10,
                  "STATUS":"OPEN","DOOR":"DOOR-01","SHIP_TO":"ACME Chicago"
                }
            """.trimIndent())
        )

        val order = repo.fetchOrder("PRD-001")

        assertEquals("PRD-001",       order.orderId)
        assertEquals("MAT-A001",      order.material)
        assertEquals("Widget Assembly", order.materialDesc)
        assertEquals(100,             order.plannedQty)
        assertEquals(20,              order.receivedQty)
        assertEquals(10,              order.palletQty)
        assertEquals("OPEN",          order.status)
        assertEquals("DOOR-01",       order.door)
    }

    @Test
    fun `fetchOrder throws on HTTP 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val ex = assertThrows(IllegalStateException::class.java) {
            runTest { repo.fetchOrder("MISSING") }
        }
        assertTrue(ex.message?.contains("404") == true)
    }

    @Test
    fun `createGR posts correct payload and parses response`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"GR_ID":"gr-uuid-001","HU_ID":"PAL-20260504-0001","ORDER_ID":"PRD-001","QTY":10}""")
        )

        val result = repo.createGR("PRD-001", 10, "J.Martinez")

        assertEquals("gr-uuid-001",       result.grId)
        assertEquals("PAL-20260504-0001", result.huId)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path?.contains("GrItems") == true)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("PRD-001"))
        assertTrue(body.contains("J.Martinez"))
    }

    @Test
    fun `createGR throws on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Error"))

        assertThrows(IllegalStateException::class.java) {
            runTest { repo.createGR("PRD-001", 10, "J.Martinez") }
        }
    }

    @Test
    fun `createDelivery posts correct fields`() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(201)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"DELIVERY_ID":"del-uuid-001"}""")
        )

        val deliveryId = repo.createDelivery("PRD-001", "DOOR-01", "ACME Chicago", 3, "J.Martinez")

        assertEquals("del-uuid-001", deliveryId)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("Delivery number should start with GR", body.contains("\"DELIVERY_NUMBER\":\"GR"))
        assertTrue(body.contains("DOOR-01"))
        assertTrue(body.contains("ACME Chicago"))
    }
}
