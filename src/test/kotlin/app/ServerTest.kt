package app

import app.db.Db
import app.server.startServer
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ServerTest {
    @Test
    fun `server serves UI, verifies, exports and re-imports`() {
        val db = Db(":memory:")
        val port = 15598
        val t = Thread { startServer(port, db) }
        t.isDaemon = true
        t.start()
        val client = HttpClient.newHttpClient()
        fun get(path: String): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).build()
            return client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        fun post(path: String, body: String): HttpResponse<String> {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build()
            return client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        // wait for server
        var up = false
        repeat(100) {
            try { if (get("/").statusCode() == 200) { up = true; return@repeat } ; Thread.sleep(100) } catch (e: Exception) { Thread.sleep(100) }
        }
        assertTrue(up, "server did not start")

        val home = get("/")
        assertTrue(home.body().contains("优化语义见证台"))

        val fixtures = get("/api/fixtures")
        assertTrue(fixtures.body().contains("inc-cmp-wrap"))

        val pair = get("/api/pair?id=overshift-select&width=8")
        assertTrue(pair.body().contains("src_overshift"))
        assertTrue(pair.body().contains("dst_overshift"))

        val verify = post("/api/verify", """{"pairId":"inc-cmp-wrap","config":{"bitWidth":8}}""")
        assertEquals(200, verify.statusCode())
        assertTrue(verify.body().contains("COUNTEREXAMPLE"))
        assertTrue(verify.body().contains("i8:127"))

        val runs = get("/api/runs")
        assertTrue(runs.body().contains("inc-cmp-wrap"))

        val exported = get("/api/export")
        assertTrue(exported.body().contains("opt-witness-export"))

        assertEquals(200, post("/api/reset", "").statusCode())
        assertTrue(!get("/api/runs").body().contains("inc-cmp-wrap"))

        val imported = post("/api/import", exported.body())
        assertTrue(imported.body().contains("\"imported\":1"))
        assertTrue(get("/api/runs").body().contains("inc-cmp-wrap"))
        db.close()
    }
}
