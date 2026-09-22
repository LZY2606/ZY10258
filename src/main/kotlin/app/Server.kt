package app

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.html.*
import java.time.Instant

private val STATUS_LABEL = mapOf(
    "EQUIVALENT" to "已证明等价",
    "COUNTEREXAMPLE" to "发现反例",
    "RESOURCE_LIMIT" to "资源上限",
    "UNKNOWN" to "求解未知",
    "UNMODELED" to "语义未建模"
)

private val STATUS_CLASS = mapOf(
    "EQUIVALENT" to "ok",
    "COUNTEREXAMPLE" to "bad",
    "RESOURCE_LIMIT" to "warn",
    "UNKNOWN" to "warn",
    "UNMODELED" to "muted"
)

fun Application.module() {
    routing {
        get("/") {
            call.respondHtml { indexPage() }
        }
        post("/run") {
            val p = call.receiveParameters()
            val fixtureId = p["fixture"] ?: Fixtures.all.first().id
            val fixture = Fixtures.byId(fixtureId) ?: Fixtures.all.first()
            val cfg = RunConfig(
                width = p["width"]?.toIntOrNull() ?: 8,
                overflowMode = p["overflowMode"] ?: "STRICT",
                floatMode = p["floatMode"] ?: "IEEE",
                unrollLimit = p["unrollLimit"]?.toIntOrNull() ?: 8,
                policy = p["policy"] ?: "REFINEMENT"
            )
            val srcIr = Fixtures.render(fixture.src, cfg.width)
            val tgtIr = Fixtures.render(fixture.tgt, cfg.width)
            val src = parseProgram(srcIr)
            val tgt = parseProgram(tgtIr)
            val result = Verifier.verify(src, tgt, cfg)
            val id = Db.insert(
                RunRecord(
                    createdAt = Instant.now().toString(),
                    fixture = fixture.id,
                    config = cfg,
                    srcIr = srcIr,
                    tgtIr = tgtIr,
                    result = result
                )
            )
            call.respondRedirect("/run/$id")
        }
        get("/run/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val rec = id?.let { Db.get(it) }
            if (rec == null) {
                call.respond(HttpStatusCode.NotFound, "run not found")
                return@get
            }
            call.respondHtml { runPage(rec) }
        }
        post("/run/{id}/minimize") {
            val id = call.parameters["id"]?.toLongOrNull()
            val rec = id?.let { Db.get(it) }
            if (rec == null) {
                call.respond(HttpStatusCode.NotFound, "run not found")
                return@post
            }
            val ce = rec.result.ce
            if (ce != null && rec.result.minimized == null) {
                val src = parseProgram(rec.srcIr)
                val tgt = parseProgram(rec.tgtIr)
                val minimized = Verifier.minimize(src, tgt, rec.config, ce)
                Db.updateResult(rec.id, rec.result.copy(minimized = minimized))
            }
            call.respondRedirect("/run/${rec.id}")
        }
        get("/export") {
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=runs-export.json")
            call.respondText(Db.exportJson(), ContentType.Application.Json)
        }
        post("/import") {
            val p = call.receiveParameters()
            val payload = p["payload"] ?: ""
            val n = try {
                Db.importJson(payload)
            } catch (e: Exception) {
                -1
            }
            call.respondRedirect(if (n >= 0) "/?imported=$n" else "/?imported=error")
        }
        post("/clear") {
            Db.clear()
            call.respondRedirect("/?cleared=1")
        }
    }
}

private fun HTML.indexPage() {
    head {
        meta { charset = "utf-8" }
        title("优化语义见证台")
        style { +PAGE_CSS }
    }
    body {
        h1 { +"优化语义见证台" }
        p("muted") { +"对受限 SSA 片段的重写前后做有界符号验证，并生成可重放反例。" }
        div("panel") {
            h2 { +"验证参数" }
            form(method = FormMethod.post, action = "/run") {
                div("grid") {
                    label {
                        +"重写对 fixture"
                        select {
                            name = "fixture"
                            Fixtures.all.forEach { f ->
                                option {
                                    value = f.id
                                    +"${f.title}（${f.id}）"
                                }
                            }
                        }
                    }
                    label {
                        +"整数位宽"
                        select {
                            name = "width"
                            listOf(4, 8, 16).forEach { w ->
                                option {
                                    value = w.toString()
                                    if (w == 8) selected = true
                                    +"i$w"
                                }
                            }
                        }
                    }
                    label {
                        +"溢出旗标"
                        select {
                            name = "overflowMode"
                            option { value = "STRICT"; +"STRICT（nsw/nuw 产生毒化）" }
                            option { value = "IGNORED"; +"IGNORED（忽略旗标，纯环绕）" }
                        }
                    }
                    label {
                        +"浮点模式"
                        select {
                            name = "floatMode"
                            option { value = "IEEE"; +"IEEE（NaN 传播）" }
                            option { value = "NNAN"; +"NNAN（NaN 即毒化）" }
                            option { value = "FAST"; +"FAST（未建模）" }
                        }
                    }
                    label {
                        +"循环展开上限"
                        select {
                            name = "unrollLimit"
                            listOf(2, 4, 8, 16).forEach { u ->
                                option {
                                    value = u.toString()
                                    if (u == 8) selected = true
                                    +"$u"
                                }
                            }
                        }
                    }
                    label {
                        +"翻译精炼口径"
                        select {
                            name = "policy"
                            option { value = "REFINEMENT"; +"REFINEMENT（源 UB 则跳过；源有定义而目标 UB 判反例）" }
                            option { value = "BOTH_DEFINED"; +"BOTH_DEFINED（仅在两侧都有定义时比较）" }
                        }
                    }
                }
                button(type = ButtonType.submit, classes = "primary") { +"运行验证" }
            }
        }
        div("panel") {
            h2 { +"运行记录" }
            p {
                a(href = "/export") { +"导出全部记录 (JSON)" }
                +" ｜ "
                form(method = FormMethod.post, action = "/clear", classes = "inline") {
                    button(type = ButtonType.submit, classes = "danger") { +"清空数据库" }
                }
            }
            form(method = FormMethod.post, action = "/import") {
                p("muted") { +"粘贴导出的 JSON 以重新导入复核：" }
                textArea {
                    name = "payload"
                    rows = "4"
                    cols = "80"
                }
                br
                button(type = ButtonType.submit) { +"导入" }
            }
            val runs = Db.list()
            if (runs.isEmpty()) {
                p("muted") { +"暂无运行记录。" }
            } else {
                table {
                    tr {
                        th { +"ID" }; th { +"时间" }; th { +"Fixture" }; th { +"位宽" }; th { +"口径" }; th { +"状态" }; th { +"已检查/总输入" }
                    }
                    runs.forEach { r ->
                        tr {
                            td { a(href = "/run/${r.id}") { +"#${r.id}" } }
                            td { +r.createdAt }
                            td { +r.fixture }
                            td { +"i${r.config.width}" }
                            td { +r.config.policy }
                            td { statusBadge(r.result.status) }
                            td { +"${r.result.checkedInputs}/${r.result.totalInputs}" }
                        }
                    }
                }
            }
        }
    }
}

private fun HTML.runPage(rec: RunRecord) {
    head {
        meta { charset = "utf-8" }
        title("优化语义见证台 - 运行 #${rec.id}")
        style { +PAGE_CSS }
    }
    body {
        h1 { +"优化语义见证台" }
        p { a(href = "/") { +"← 返回首页" } }
        val fixture = Fixtures.byId(rec.fixture)
        div("panel") {
            h2 { +"运行 #${rec.id} — ${fixture?.title ?: rec.fixture}" }
            if (fixture != null) p("muted") { +fixture.description }
            p("muted") {
                +("位宽 i${rec.config.width} ｜ 溢出旗标 ${rec.config.overflowMode} ｜ 浮点模式 ${rec.config.floatMode} ｜ " +
                    "展开上限 ${rec.config.unrollLimit} ｜ 口径 ${rec.config.policy} ｜ ${rec.createdAt}")
            }
            p {
                statusBadge(rec.result.status)
                +"  ${STATUS_LABEL[rec.result.status] ?: rec.result.status}（已检查 ${rec.result.checkedInputs}/${rec.result.totalInputs} 个输入）"
            }
            if (rec.result.note.isNotEmpty()) p("muted") { +"备注：${rec.result.note}" }
        }
        div("cols") {
            div("col") {
                h3 { +"重写前（源）" }
                h4 { +"控制流" }
                pre { +rec.srcIr }
                h4 { +"值范围" }
                rangeTable(rec.result.srcRanges)
                h4 { +"路径条件" }
                pathList(rec.result.srcPaths)
            }
            div("col") {
                h3 { +"重写后（目标）" }
                h4 { +"控制流" }
                pre { +rec.tgtIr }
                h4 { +"值范围" }
                rangeTable(rec.result.tgtRanges)
                h4 { +"路径条件" }
                pathList(rec.result.tgtPaths)
            }
        }
        val ce = rec.result.ce
        if (ce != null) {
            div("panel ce") {
                h2 { +"反例（可重放）" }
                ceBlock("原始反例", ce)
                val minimized = rec.result.minimized
                if (minimized != null) {
                    ceBlock("缩减后（原例保留）", minimized)
                } else {
                    form(method = FormMethod.post, action = "/run/${rec.id}/minimize") {
                        button(type = ButtonType.submit) { +"缩减反例（保留原例）" }
                    }
                }
            }
        }
    }
}

private fun FlowContent.ceBlock(title: String, ce: CounterExample) {
    h3 { +title }
    p { +"原因：" ; code { +ce.reason } }
    h4 { +"输入" }
    table {
        tr { th { +"参数" }; th { +"值" } }
        ce.inputs.forEach { (k, v) -> tr { td { +k }; td { +v } } }
    }
    div("cols") {
        div("col") {
            h4 { +"源：路径与中间值" }
            p("muted") { +"路径：${ce.pathSrc.joinToString(" -> ")}" }
            if (ce.condSrc.isNotEmpty()) p("muted") { +"路径条件：${ce.condSrc.joinToString(", ")}" }
            pre { +ce.traceSrc.joinToString("\n") }
            p { +"结果：" ; code { +ce.srcResult } }
        }
        div("col") {
            h4 { +"目标：路径与中间值" }
            p("muted") { +"路径：${ce.pathTgt.joinToString(" -> ")}" }
            if (ce.condTgt.isNotEmpty()) p("muted") { +"路径条件：${ce.condTgt.joinToString(", ")}" }
            pre { +ce.traceTgt.joinToString("\n") }
            p { +"结果：" ; code { +ce.tgtResult } }
        }
    }
}

private fun FlowContent.rangeTable(ranges: Map<String, ValueRange>) {
    if (ranges.isEmpty()) {
        p("muted") { +"（无）" }
        return
    }
    table {
        tr { th { +"值" }; th { +"最小" }; th { +"最大" }; th { +"NaN" } }
        ranges.forEach { (k, v) ->
            tr {
                td { +k }
                td { +v.min }
                td { +v.max }
                td { +if (v.sawNaN) "是" else "否" }
            }
        }
    }
}

private fun FlowContent.pathList(paths: List<String>) {
    if (paths.isEmpty()) {
        p("muted") { +"（无）" }
        return
    }
    ul { paths.forEach { li { code { +it } } } }
}

private fun FlowContent.statusBadge(status: String) {
    span("badge ${STATUS_CLASS[status] ?: "muted"}") { +(STATUS_LABEL[status] ?: status) }
}

private val PAGE_CSS = """
body { font-family: -apple-system, "PingFang SC", sans-serif; margin: 2rem; color: #222; }
h1 { font-size: 1.6rem; }
h2 { font-size: 1.15rem; }
.panel { border: 1px solid #ddd; border-radius: 8px; padding: 1rem 1.25rem; margin-bottom: 1.25rem; }
.panel.ce { border-color: #d33; }
.cols { display: flex; gap: 1.25rem; }
.col { flex: 1; border: 1px solid #ddd; border-radius: 8px; padding: 1rem; min-width: 0; }
pre { background: #f6f8fa; padding: .75rem; border-radius: 6px; overflow-x: auto; font-size: .85rem; }
table { border-collapse: collapse; margin: .5rem 0; }
th, td { border: 1px solid #ddd; padding: .3rem .6rem; font-size: .85rem; text-align: left; }
.badge { display: inline-block; padding: .15rem .6rem; border-radius: 10px; font-size: .85rem; font-weight: 600; }
.badge.ok { background: #d3f9d8; color: #2b8a3e; }
.badge.bad { background: #ffe3e3; color: #c92a2a; }
.badge.warn { background: #fff3bf; color: #e67700; }
.badge.muted { background: #e9ecef; color: #495057; }
.muted { color: #666; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: .6rem 1.5rem; margin-bottom: .8rem; }
label { display: flex; flex-direction: column; font-size: .85rem; gap: .25rem; }
select, textarea { font-size: .9rem; padding: .3rem; }
button { padding: .4rem 1rem; font-size: .9rem; border-radius: 6px; border: 1px solid #aaa; background: #f1f3f5; cursor: pointer; }
button.primary { background: #1971c2; color: #fff; border-color: #1971c2; }
button.danger { background: #c92a2a; color: #fff; border-color: #c92a2a; }
form.inline { display: inline; }
code { background: #f1f3f5; padding: .1rem .3rem; border-radius: 4px; }
""".trimIndent()
