package app

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = "data/witness.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(i + 1)?.toIntOrNull() ?: 8080
            "--db" -> dbPath = args.getOrNull(i + 1) ?: dbPath
        }
        i++
    }
    Db.init(dbPath)
    println("优化语义见证台 listening on http://127.0.0.1:$port")
    embeddedServer(Netty, port = port, host = "127.0.0.1") { module() }.start(wait = true)
}
