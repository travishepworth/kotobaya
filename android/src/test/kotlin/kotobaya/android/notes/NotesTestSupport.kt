package kotobaya.android.notes

import kotlin.random.Random
import kotlin.test.assertIs
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.serverReplica
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.UuidV7Source

const val DEVICE_A = "01931f3e-0000-7000-8000-0000000000a1"
const val DEVICE_B = "01931f3e-0000-7000-8000-0000000000b2"
const val T0 = 1_700_000_000_000L

/** A [CandidateSource] over a fixed list (what [InstalledCandidateSource] returns, without a file). */
class FakeSource(private val entries: List<EntryCandidate>, private val available: Boolean = true) : CandidateSource {
    override fun isAvailable(): Boolean = available

    override fun candidates(text: String): List<EntryCandidate> {
        val f = EntryMatcher.fold(text)
        return entries.filter { e -> e.kanjiForms.any { EntryMatcher.fold(it) == f } || e.readings.any { EntryMatcher.fold(it) == f } }
    }
}

fun cand(seq: Long, word: String, reading: String, glosses: List<String> = listOf("gloss $seq"), common: Boolean = true, rank: Int? = null) =
    EntryCandidate(seq, word, reading, if (word != reading) listOf(word) else emptyList(), listOf(reading), glosses, common, rank)

val DICT: List<EntryCandidate> = listOf(
    cand(1358280, "食べる", "たべる", listOf("to eat"), rank = 100),
    cand(1358300, "食べ物", "たべもの", listOf("food"), rank = 120),
    cand(1489780, "猫", "ねこ", listOf("cat"), rank = 200),
)

fun creator(d: SqlDevice, source: CandidateSource = FakeSource(DICT), seed: Int = 7, afterCreate: () -> Unit = {}) =
    NoteCreator<SqlLearningTables>(d.store, d.stack.minter, EntryMatcher(source), UuidV7Source(Random(seed)), d.clock, afterCreate = afterCreate)

fun duplicateCheck(d: SqlDevice, source: CandidateSource = FakeSource(DICT)) = DuplicateCheck(
    object : LearningReader {
        override fun <R> read(block: (LearningTables) -> R): R = d.store.read { block(it.learning) }
    },
    EntryMatcher(source),
)

/** Mints a user deck on [d] (with [settings]) and returns its id. */
fun userDeck(d: SqlDevice, id: String, settings: Map<String, JsonValue> = emptyMap()): String {
    val payload = JsonObject(linkedMapOf("id" to JsonString(id), "name" to JsonString("Mined"), "type" to JsonString("user"), "settings" to JsonObject(settings)))
    assertIs<MintResult.Minted>(d.stack.minter.mint(OpType.DECK_CREATED, payload))
    return id
}

fun str(row: Row, col: String): String? = (row[col] as? JsonString)?.value

fun isLive(row: Row): Boolean = (row["deleted_at"] ?: JsonNull) == JsonNull && (row["merged_into_id"] ?: JsonNull) == JsonNull

fun notes(t: LearningTables): List<Row> = t.all(LearningTable.NOTE)

fun cards(t: LearningTables, noteId: String): List<Row> = t.where(LearningTable.CARD, "note_id", JsonString(noteId))

fun stringList(v: JsonValue?): List<String> = (v as? JsonArray)?.items.orEmpty().map { (it as JsonString).value }

/**
 * The server's duplicate merge of SYNC.md 8.1, for [TestServer.emitAfter]: run on the server replica
 * (the log so far, triggering op included). Note collisions on `entry_seq`, cards created on a merged
 * note, and card collisions; emitted ops minted with the server clock after the trigger.
 */
fun mergeEmitter(server: TestServer): (Op) -> List<Op> = { op ->
    val t = serverReplica(server)
    var hlc = server.hlc
    val out = ArrayList<Op>()
    fun emit(type: OpType, vararg members: Pair<String, String>) {
        val minted = DefaultHlcRules.mint(hlc, server.nowMs, Reserved.SERVER_DEVICE_ID)
        hlc = minted.state
        val payload = JsonObject(linkedMapOf(*members.map { it.first to (JsonString(it.second) as JsonValue) }.toTypedArray()))
        out += Op(
            opId = UuidV7Source(Random(server.log.size * 101 + out.size)).newOpId(server.nowMs),
            opType = type,
            opVersion = OpType.MAX_SUPPORTED_VERSION,
            entityType = type.entityType,
            entityId = DefaultOpCodec.entityIdOf(type, payload),
            deviceId = Reserved.SERVER_DEVICE_ID,
            hlc = minted.hlc,
            payload = payload,
        )
    }
    /** Live card of [noteId] of [kind] on the replica, or one this batch created. */
    val created = HashMap<Pair<String, String>, String>()
    fun liveCard(noteId: String, kind: String): String? = created[noteId to kind]
        ?: cards(t, noteId).firstOrNull { (it["deleted_at"] ?: JsonNull) == JsonNull && str(it, "kind") == kind }?.let { str(it, "id") }
    fun mergeCard(cardId: String, survivorNote: String, kind: String) {
        val survivor = liveCard(survivorNote, kind) ?: run {
            val id = UuidV7Source(Random(cardId.hashCode())).newOpId(server.nowMs)
            emit(OpType.CARD_CREATED, "id" to id, "note_id" to survivorNote, "kind" to kind)
            created[survivorNote to kind] = id
            id
        }
        emit(OpType.CARD_MERGED, "loser_id" to cardId, "survivor_id" to survivor)
    }
    when (op.opType) {
        OpType.NOTE_CREATED -> {
            val n = t.get(LearningTable.NOTE, listOf(JsonString(op.entityId)))
            val seq = n?.get("entry_seq")
            if (n != null && isLive(n) && seq != null && seq != JsonNull) {
                val w = t.where(LearningTable.NOTE, "entry_seq", seq).filter { isLive(it) && str(it, "id") != op.entityId }
                    .minByOrNull { str(it, "id")!! }
                if (w != null) {
                    val wid = str(w, "id")!!
                    emit(OpType.NOTE_MERGED, "loser_id" to op.entityId, "survivor_id" to wid)
                    cards(t, op.entityId).filter(::isLive).sortedWith(compareBy({ str(it, "kind") }, { str(it, "id") }))
                        .forEach { mergeCard(str(it, "id")!!, wid, str(it, "kind")!!) }
                }
            }
        }
        OpType.CARD_CREATED -> {
            val c = t.get(LearningTable.CARD, listOf(JsonString(op.entityId)))!!
            val noteId = str(c, "note_id")!!
            val kind = str(c, "kind")!!
            val note = t.get(LearningTable.NOTE, listOf(JsonString(noteId)))
            val w = note?.let { str(it, "merged_into_id") }
            when {
                w != null -> mergeCard(op.entityId, w, kind)
                else -> cards(t, noteId).firstOrNull { (it["deleted_at"] ?: JsonNull) == JsonNull && str(it, "kind") == kind && str(it, "id") != op.entityId }
                    ?.let { emit(OpType.CARD_MERGED, "loser_id" to op.entityId, "survivor_id" to str(it, "id")!!) }
            }
        }
        else -> Unit
    }
    out
}
