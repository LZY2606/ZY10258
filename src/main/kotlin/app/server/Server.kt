package app.server

import app.db.Db
import app.db.StoredResult
import app.db.StoredRun
import app.fixtures.Fixtures
import app.ir.render
import app.verify.Minimizer
import app.verify.PairResult
import app.verify.Verifier
import app.verify.VerifyConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VerifyRequest(val pairId: String = "all", val config: VerifyConfig = VerifyConfig())

@Serializable
data class VerifyResponse(val runId: Long, val results: List<PairResult>,
                          val minimized: Map<String, app.verify.CounterExample?>)

@Serializable
data class FixtureInfo(val id: String, val title: String, val description: String)

@Serializable
data class PairView(val id: String, val title: String, val description: String,
                    val srcText: String, val dstText: String)

object JsonCodec {
    val json = Json { prettyPrint = false; encodeDefaults = true }
}

fun startServer(port: Int, db: Db) {
    val json = JsonCodec.json
    val indexHtml = object {}.javaClass.getResource("/web/index.html")?.readText()
        ?: error("web/index.html resource missing")

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        routing {
            get("/") {
                call.respondText(indexHtml, ContentType.Text.Html)
            }
            get("/api/fixtures") {
                val list = Fixtures.all.map { FixtureInfo(it.id, it.title, it.description) }
                call.respondText(json.encodeToString(list), ContentType.Application.Json)
            }
            get("/api/pair") {
                val id = call.request.queryParameters["id"] ?: return@get call.respondText(
                    "missing id", status = HttpStatusCode.BadRequest)
                val width = call.request.queryParameters["width"]?.toIntOrNull() ?: 8
                val fx = Fixtures.byId(id) ?: return@get call.respondText(
                    "unknown fixture", status = HttpStatusCode.NotFound)
                val pair = fx.build(width)
                call.respondText(json.encodeToString(
                    PairView(fx.id, fx.title, fx.description, render(pair.src), render(pair.dst))),
                    ContentType.Application.Json)
            }
            post("/api/verify") {
                val req = try {
                    json.decodeFromString<VerifyRequest>(call.receiveText())
                } catch (e: Exception) {
                    return@post call.respondText("bad request: ${e.message}", status = HttpStatusCode.BadRequest)
                }
                val fixtures = if (req.pairId == "all") Fixtures.all
                else listOfNotNull(Fixtures.byId(req.pairId)).ifEmpty {
                    return@post call.respondText("unknown fixture", status = HttpStatusCode.NotFound)
                }
                val verifier = Verifier(req.config)
                val results = mutableListOf<PairResult>()
                val minimized = mutableMapOf<String, app.verify.CounterExample?>()
                for (fx in fixtures) {
                    val pair = fx.build(req.config.bitWidth)
                    val r = verifier.verify(pair)
                    results.add(r)
                    if (r.ce != null) minimized[fx.id] = Minimizer.minimize(pair, req.config, r.ce)
                }
                val runId = db.insertRun(req.config, results, minimized)
                call.respondText(json.encodeToString(VerifyResponse(runId, results, minimized)),
                    ContentType.Application.Json)
            }
            get("/api/runs") {
                call.respondText(json.encodeToString(db.listRuns()), ContentType.Application.Json)
            }
            get("/api/runs/{id}") {
                val id = call.pathParameters["id"]?.toLongOrNull()
                    ?: return@get call.respondText("bad id", status = HttpStatusCode.BadRequest)
                val run = db.getRun(id)
                    ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
                call.respondText(json.encodeToString(StoredRun.serializer(), run), ContentType.Application.Json)
            }
            get("/api/export") {
                call.response.headers.append("Content-Disposition", "attachment; filename=witness-runs.json")
                call.respondText(db.exportJson(), ContentType.Application.Json)
            }
            post("/api/import") {
                val body = call.receiveText()
                try {
                    val n = db.importJson(body)
                    call.respondText("""{"imported":$n}""", ContentType.Application.Json)
                } catch (e: Exception) {
                    call.respondText("""{"error":"${e.message?.replace("\"", "'")}"}""",
                        ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            }
            post("/api/reset") {
                db.reset()
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}
