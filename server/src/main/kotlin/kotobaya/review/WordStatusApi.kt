package kotobaya.review

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext

private val OVERRIDES = setOf("known", "never_forget", "blacklisted", "ignored")

/** `getWordStatus`, `setWordStatus`, `clearWordStatus`: explicit overrides are `word.status_set` / `word.status_cleared` ops. */
internal class WordStatusApi(private val db: Database, private val opApply: OpApply) {
    suspend fun get(call: ApplicationCall) {
        val seq = seq(call)
        call.respondJson(db.tx { status(it, seq) })
    }

    suspend fun set(call: ApplicationCall) {
        val seq = seq(call)
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("status"))
        val status = e.enum(body, "status", OVERRIDES, required = true)
        e.throwIfAny()
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            // Setting the status it already has still emits: the register's clock moves forward.
            tx.emit(OpType.WORD_STATUS_SET, payload(json.createObjectNode().put("entry_seq", seq).put("status", status).put("origin", "manual")))
            Reply(HttpStatusCode.OK, json.valueToTree(status(tx.dsl, seq)), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun clear(call: ApplicationCall) {
        val seq = seq(call)
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val has = tx.dsl.fetchExists(tx.dsl.selectOne().from("app.word_status").where("entry_seq = ? and override_status is not null", seq))
            if (!has) return@rest Reply(HttpStatusCode.OK, json.valueToTree(status(tx.dsl, seq)))
            tx.emit(OpType.WORD_STATUS_CLEARED, payload(json.createObjectNode().put("entry_seq", seq)))
            Reply(HttpStatusCode.OK, json.valueToTree(status(tx.dsl, seq)), tx.lastSeq)
        }
        call.send(reply)
    }

    private fun seq(call: ApplicationCall): Long {
        val seq = call.parameters["seq"]?.toLongOrNull()?.takeIf { it >= 1 }
        if (seq == null) Errors().apply { add("/seq", "invalid_value", "Must be an integer of at least 1.") }.throwIfAny()
        return checkNotNull(seq)
    }

    /** `WordStatus`; an entry without a row is `new` with every other property null. */
    private fun status(dsl: DSLContext, seq: Long): Map<String, Any?> {
        val r = dsl.fetchOne(
            "select status, auto_status, override_status, override_origin, " +
                "to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from app.word_status where entry_seq = ?",
            seq,
        )
        return linkedMapOf(
            "entry_seq" to seq,
            "status" to (r?.get(0, String::class.java) ?: "new"),
            "auto_status" to r?.get(1, String::class.java),
            "override_status" to r?.get(2, String::class.java),
            "override_origin" to r?.get(3, String::class.java),
            "updated_at" to r?.get(4, String::class.java),
        )
    }
}
