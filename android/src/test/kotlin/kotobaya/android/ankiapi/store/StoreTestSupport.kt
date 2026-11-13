package kotobaya.android.ankiapi.store

import android.content.Context
import kotlin.random.Random
import kotobaya.android.ankiapi.takoboto.NoteTypeEnsurer
import kotobaya.android.notes.CandidateSource
import kotobaya.android.notes.DICT
import kotobaya.android.notes.DuplicateCheck
import kotobaya.android.notes.EntryMatcher
import kotobaya.android.notes.FakeSource
import kotobaya.android.notes.LearningReader
import kotobaya.android.notes.creator
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.contracts.sync.JsonObject
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.UuidV7Source

/** The dictionary of the notes tests ([DICT]) as the store's [EntryLookup]. */
val FAKE_ENTRIES: EntryLookup = EntryLookup { seq ->
    DICT.firstOrNull { it.seq == seq }?.let { Headword(it.headword, it.headwordReading, it.glosses.joinToString("; ")) }
}

fun ensurer(d: SqlDevice, seed: Int = 3): NoteTypeEnsurer<SqlLearningTables> =
    NoteTypeEnsurer(d.store, d.stack.minter, UuidV7Source(Random(seed)), d.clock)

/** The production store wired over test device [d] (rebuild it after [SqlDevice.restart]). */
fun takobotoStore(
    d: SqlDevice,
    map: AnkiIdMap,
    source: CandidateSource = FakeSource(DICT),
    seed: Int = 7,
): TakobotoAnkiStore<SqlLearningTables> {
    val reader = object : LearningReader {
        override fun <R> read(block: (LearningTables) -> R): R = d.store.read { block(it.learning) }
    }
    return TakobotoAnkiStore(
        store = d.store,
        reader = reader,
        minter = d.stack.minter,
        noteTypes = ensurer(d, seed),
        creator = creator(d, source, seed),
        duplicates = DuplicateCheck(reader, EntryMatcher(source)),
        map = map,
        entries = FAKE_ENTRIES,
        ids = UuidV7Source(Random(seed + 1)),
        clock = d.clock,
    )
}

fun memoryMap(context: Context): SqliteAnkiIdMap = SqliteAnkiIdMap(context, null)

/** A pending (not yet pushed) op of a device: its wire type, entity id and payload. */
data class Pending(val type: String, val entityId: String, val payload: JsonObject)

/** Pending ops of [d], in hlc order. */
fun pendingOps(d: SqlDevice): List<Pending> = d.store.read { tx -> tx.outbox.inState(PendingState.PENDING) }.sortedBy { it.hlc }.map { row ->
    Pending(row.opType, row.entityId, DefaultCanonicalJson.decode(row.payload).value as JsonObject)
}

fun countOf(ops: List<Pending>, type: OpType): Int = ops.count { it.type == type.wire }
