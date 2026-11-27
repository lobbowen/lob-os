package lobos.system.exec

import java.io.File
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import lobos.system.fs.SystemDirs
import lobos.system.fs.StateFiles

object SessionRegistry {

    private fun dir(): File = SystemDirs.run()
    private const val FILE = "sessions.json"
    private const val SCHEMA = 1
    private const val GRACE_MS = 20_000L
    const val BASE_SOCKET = "lobos_hostbridge"

    data class Session(
        val id: String,
        val programId: String,
        val generation: Long,
        val token: String,
        val pid: Int,
        val starttime: Long,
        val issuedAt: Long,
        val claimedAt: Long = 0L,
    )

    private val rng = SecureRandom()

    private fun file(): File {
        val d = dir()
        d.mkdirs()
        return File(d, FILE)
    }

    private fun read(): JSONObject =
        runCatching { JSONObject(file().readText()) }.getOrDefault(JSONObject())

    private fun write(obj: JSONObject) {
        runCatching {
            obj.put("schema", SCHEMA)
            StateFiles.writeAtomic(file(), obj.toString(2))
        }
    }

    @Synchronized
    fun list(): List<Session> {
        val arr = read().optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Session(
                id = o.optString("id", ""),
                programId = o.optString("programId", ""),
                generation = o.optLong("generation", 0L),
                token = o.optString("token", ""),
                pid = o.optInt("pid", 0),
                starttime = o.optLong("starttime", -1L),
                issuedAt = o.optLong("issuedAt", 0L),
                claimedAt = o.optLong("claimedAt", 0L),
            )
        }
    }

    @Synchronized
    private fun persist(sessions: List<Session>) {
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("programId", s.programId)
                put("generation", s.generation)
                put("token", s.token)
                put("pid", s.pid)
                put("starttime", s.starttime)
                put("issuedAt", s.issuedAt)
                put("claimedAt", s.claimedAt)
            })
        }
        write(read().put("sessions", arr))
    }

    private fun newToken(): String {
        val b = ByteArray(24)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun issue(programId: String, generation: Long): String {
        val token = newToken()
        val s = Session(
            id = programId + "#" + generation,
            programId = programId,
            generation = generation,
            token = token,
            pid = 0,
            starttime = -1L,
            issuedAt = System.currentTimeMillis(),
        )
        persist(list().filterNot { it.programId == programId && it.generation == generation } + s)
        return token
    }

    @Synchronized
    fun bindPid(token: String, pid: Int): Session? {
        val cur = list()
        val st = ProcessLedger.starttimeOf(pid)
        var out: Session? = null
        persist(cur.map {
            if (it.token == token) {
                val upd = it.copy(pid = pid, starttime = st)
                out = upd
                upd
            } else it
        })
        return out
    }

    fun verify(token: String?): Session? {
        if (token.isNullOrBlank()) return null
        val s = list().firstOrNull { it.token == token } ?: return null
        if (s.pid <= 0) {
            return if (System.currentTimeMillis() - s.issuedAt <= GRACE_MS) s else null
        }
        return if (ProcessLedger.isOwnedAlive(s.pid, s.starttime)) s else null
    }

    @Synchronized
    fun clear() = persist(emptyList())

    /** Remove a single token (used when a management session ends). */
    fun remove(token: String) {
        val kept = list().filterNot { it.token == token }
        persist(kept)
    }

    fun socketName(token: String): String = BASE_SOCKET + "." + token.take(16)

    @Synchronized
    fun claim(token: String): Boolean {
        val cur = list()
        val s = cur.firstOrNull { it.token == token } ?: return false
        if (s.claimedAt > 0L) return false
        persist(cur.map { if (it.token == token) it.copy(claimedAt = System.currentTimeMillis()) else it })
        return true
    }

}
