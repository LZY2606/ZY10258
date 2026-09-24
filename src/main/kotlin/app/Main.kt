package app

import app.db.Db
import app.server.startServer

fun main(args: Array<String>) {
    var port = 8080
    var dbPath = "data/witness.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
            else -> { System.err.println("unknown arg: ${args[i]}"); kotlin.system.exitProcess(2) }
        }
        i++
    }
    val db = Db(dbPath)
    println("优化语义见证台 listening on http://127.0.0.1:$port (db=$dbPath)")
    startServer(port, db)
}
