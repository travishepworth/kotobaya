package kotobaya.grammar

import kotobaya.contracts.sync.OpType
import kotobaya.notes.payload
import kotobaya.server.json
import kotobaya.sync.OpApply
import java.util.UUID

/**
 * Loads the seed stubs (`grammar-seed/grammar-seed.json`, a copy of `fixtures/grammar-seed/`) as `grammar.created`
 * ops with `is_stub = true`. Ids are derived from (jlpt, pattern, title), so seeding twice mints nothing, and a seed
 * point the user deleted stays deleted (the tombstoned row still holds the id).
 * Call from a one-off step: `GrammarSeeder.seed(opApply)`; it returns the number of points created.
 */
object GrammarSeeder {
    private const val RESOURCE = "/grammar-seed/grammar-seed.json"

    data class Stub(val title: String, val pattern: String, val jlpt: Int) {
        val id: String get() = UUID.nameUUIDFromBytes("kotobaya-grammar-seed|$jlpt|$pattern|$title".toByteArray(Charsets.UTF_8)).toString()
    }

    fun load(): List<Stub> {
        val text = GrammarSeeder::class.java.getResourceAsStream(RESOURCE)?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("missing resource $RESOURCE")
        return json.readTree(text).map { Stub(it["title"].asText(), it["pattern"].asText(), it["jlpt"].asInt()) }
    }

    suspend fun seed(opApply: OpApply, tokenId: UUID? = null): Int = opApply.transaction(tokenId) { tx ->
        var created = 0
        for (s in load()) {
            val exists = tx.dsl.fetchExists(tx.dsl.selectOne().from("app.grammar_point").where("id = ?::uuid", s.id))
            if (exists) continue
            val p = json.createObjectNode().put("id", s.id).put("title", s.title).put("pattern", s.pattern).put("jlpt", s.jlpt).put("is_stub", true)
            tx.emit(OpType.GRAMMAR_CREATED, payload(p))
            created++
        }
        created
    }
}
