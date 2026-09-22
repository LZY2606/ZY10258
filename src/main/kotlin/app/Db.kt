package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class RunRecord(
    val id: Long = 0,
    val createdAt: String,
    val fixture: String,
    val config: RunConfig,
    val srcIr: String,
    val tgtIr: String,
    val result: VerifyResult
)

object Db {
    private var conn: Connection? = null
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun init(path: String = "data/witness.db") {
        val f = java.io.File(path)
        f.parentFile?.mkdirs()
        val c = DriverManager.getConnection("jdbc:sqlite:${f.path}")
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS runs(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  created_at TEXT NOT NULL,
                  fixture TEXT NOT NULL,
                  config TEXT NOT NULL,
                  src_ir TEXT NOT NULL,
                  tgt_ir TEXT NOT NULL,
                  result TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        conn = c
    }

    private fun c(): Connection = conn ?: error("Db not initialized")

    fun insert(rec: RunRecord): Long {
        val sql = "INSERT INTO runs(created_at, fixture, config, src_ir, tgt_ir, result) VALUES(?,?,?,?,?,?)"
        c().prepareStatement(sql).use { ps ->
            ps.setString(1, rec.createdAt)
            ps.setString(2, rec.fixture)
            ps.setString(3, json.encodeToString(RunConfig.serializer(), rec.config))
            ps.setString(4, rec.srcIr)
            ps.setString(5, rec.tgtIr)
            ps.setString(6, json.encodeToString(VerifyResult.serializer(), rec.result))
            ps.executeUpdate()
        }
        c().createStatement().use { st ->
            val rs = st.executeQuery("SELECT last_insert_rowid()")
            rs.next()
            return rs.getLong(1)
        }
    }

    fun get(id: Long): RunRecord? {
        c().prepareStatement("SELECT id, created_at, fixture, config, src_ir, tgt_ir, result FROM runs WHERE id=?").use { ps ->
            ps.setLong(1, id)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            return rowToRecord(rs)
        }
    }

    fun list(limit: Int = 50): List<RunRecord> {
        val out = mutableListOf<RunRecord>()
        c().prepareStatement("SELECT id, created_at, fixture, config, src_ir, tgt_ir, result FROM runs ORDER BY id DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            while (rs.next()) out.add(rowToRecord(rs))
        }
        return out
    }

    private fun rowToRecord(rs: java.sql.ResultSet): RunRecord = RunRecord(
        id = rs.getLong(1),
        createdAt = rs.getString(2),
        fixture = rs.getString(3),
        config = json.decodeFromString(RunConfig.serializer(), rs.getString(4)),
        srcIr = rs.getString(5),
        tgtIr = rs.getString(6),
        result = json.decodeFromString(VerifyResult.serializer(), rs.getString(7))
    )

    fun updateResult(id: Long, result: VerifyResult) {
        c().prepareStatement("UPDATE runs SET result=? WHERE id=?").use { ps ->
            ps.setString(1, json.encodeToString(VerifyResult.serializer(), result))
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    fun clear() {
        c().createStatement().use { it.execute("DELETE FROM runs") }
    }

    fun exportJson(): String {
        val all = list(10_000)
        return Json { prettyPrint = true }.encodeToString(kotlinx.serialization.builtins.ListSerializer(RunRecord.serializer()), all)
    }

    fun importJson(payload: String): Int {
        val records = Json { ignoreUnknownKeys = true }.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(RunRecord.serializer()), payload
        )
        var n = 0
        for (r in records) {
            insert(r.copy(id = 0))
            n++
        }
        return n
    }
}
