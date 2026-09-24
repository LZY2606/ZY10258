package app.db

import app.verify.CounterExample
import app.verify.PairResult
import app.verify.Status
import app.verify.VerifyConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

@Serializable
data class StoredResult(
    val pairId: String,
    val status: Status,
    val explored: Long,
    val note: String,
    val result: PairResult,
    val originalCe: CounterExample?,
    val minimizedCe: CounterExample?
)

@Serializable
data class StoredRun(
    val id: Long,
    val createdAt: String,
    val config: VerifyConfig,
    val results: List<StoredResult>
)

@Serializable
data class ExportBundle(val format: String = "opt-witness-export", val version: Int = 1, val runs: List<StoredRun>)

class Db(path: String) {
    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private val conn: Connection

    init {
        if (path != ":memory:") {
            val f = java.io.File(path)
            f.parentFile?.mkdirs()
        }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS runs(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     created_at TEXT NOT NULL,
                     config_json TEXT NOT NULL)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS results(
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                     pair_id TEXT NOT NULL,
                     status TEXT NOT NULL,
                     explored INTEGER NOT NULL,
                     note TEXT NOT NULL,
                     result_json TEXT NOT NULL,
                     original_ce_json TEXT,
                     minimized_ce_json TEXT)"""
            )
        }
    }

    fun insertRun(config: VerifyConfig, results: List<PairResult>,
                  minimized: Map<String, CounterExample?>): Long {
        conn.autoCommit = false
        try {
            val runId: Long
            conn.prepareStatement("INSERT INTO runs(created_at, config_json) VALUES(?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                ps.setString(1, Instant.now().toString())
                ps.setString(2, json.encodeToString(VerifyConfig.serializer(), config))
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> rs.next(); runId = rs.getLong(1) }
            }
            conn.prepareStatement(
                "INSERT INTO results(run_id, pair_id, status, explored, note, result_json, original_ce_json, minimized_ce_json) VALUES(?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (r in results) {
                    ps.setLong(1, runId)
                    ps.setString(2, r.pairId)
                    ps.setString(3, r.status.name)
                    ps.setLong(4, r.explored)
                    ps.setString(5, r.note)
                    ps.setString(6, json.encodeToString(PairResult.serializer(), r))
                    ps.setString(7, r.ce?.let { json.encodeToString(CounterExample.serializer(), it) })
                    ps.setString(8, minimized[r.pairId]?.let { json.encodeToString(CounterExample.serializer(), it) })
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
            return runId
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun listRuns(): List<StoredRun> {
        val runs = mutableListOf<StoredRun>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id, created_at, config_json FROM runs ORDER BY id DESC").use { rs ->
                while (rs.next()) {
                    runs.add(loadRun(rs.getLong(1), rs.getString(2), rs.getString(3)))
                }
            }
        }
        return runs
    }

    fun getRun(id: Long): StoredRun? {
        conn.prepareStatement("SELECT id, created_at, config_json FROM runs WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                return if (rs.next()) loadRun(rs.getLong(1), rs.getString(2), rs.getString(3)) else null
            }
        }
    }

    private fun loadRun(id: Long, createdAt: String, configJson: String): StoredRun {
        val results = mutableListOf<StoredResult>()
        conn.prepareStatement("SELECT pair_id, status, explored, note, result_json, original_ce_json, minimized_ce_json FROM results WHERE run_id = ? ORDER BY id").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(StoredResult(
                        pairId = rs.getString(1),
                        status = Status.valueOf(rs.getString(2)),
                        explored = rs.getLong(3),
                        note = rs.getString(4),
                        result = json.decodeFromString(PairResult.serializer(), rs.getString(5)),
                        originalCe = rs.getString(6)?.let { json.decodeFromString(CounterExample.serializer(), it) },
                        minimizedCe = rs.getString(7)?.let { json.decodeFromString(CounterExample.serializer(), it) }
                    ))
                }
            }
        }
        return StoredRun(id, createdAt, json.decodeFromString(VerifyConfig.serializer(), configJson), results)
    }

    fun exportJson(): String = json.encodeToString(ExportBundle.serializer(), ExportBundle(runs = listRuns().reversed()))

    fun importJson(body: String): Int {
        val bundle = json.decodeFromString(ExportBundle.serializer(), body)
        require(bundle.format == "opt-witness-export") { "unknown export format: ${bundle.format}" }
        var count = 0
        conn.autoCommit = false
        try {
            for (run in bundle.runs) {
                val runId: Long
                conn.prepareStatement("INSERT INTO runs(created_at, config_json) VALUES(?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
                    ps.setString(1, run.createdAt)
                    ps.setString(2, json.encodeToString(VerifyConfig.serializer(), run.config))
                    ps.executeUpdate()
                    ps.generatedKeys.use { rs -> rs.next(); runId = rs.getLong(1) }
                }
                conn.prepareStatement(
                    "INSERT INTO results(run_id, pair_id, status, explored, note, result_json, original_ce_json, minimized_ce_json) VALUES(?,?,?,?,?,?,?,?)"
                ).use { ps ->
                    for (r in run.results) {
                        ps.setLong(1, runId)
                        ps.setString(2, r.pairId)
                        ps.setString(3, r.status.name)
                        ps.setLong(4, r.explored)
                        ps.setString(5, r.note)
                        ps.setString(6, json.encodeToString(PairResult.serializer(), r.result))
                        ps.setString(7, r.originalCe?.let { json.encodeToString(CounterExample.serializer(), it) })
                        ps.setString(8, r.minimizedCe?.let { json.encodeToString(CounterExample.serializer(), it) })
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                count++
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return count
    }

    fun reset() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM results")
            st.executeUpdate("DELETE FROM runs")
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('runs','results')")
        }
    }

    fun close() = conn.close()
}
