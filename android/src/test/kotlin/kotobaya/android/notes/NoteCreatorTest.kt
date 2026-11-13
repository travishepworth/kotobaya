package kotobaya.android.notes

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.sync.Killed
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.Stack
import kotobaya.android.sync.TestServer
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source

class NoteCreatorTest {
    private val deckId = "0193a1b2-0004-7000-8000-000000000001"

    private fun device(): SqlDevice = SqlDevice(DEVICE_A, TestServer(T0), T0)

    private fun pending(d: SqlDevice): Int = d.store.read { it.outbox.count(PendingState.PENDING) }

    private val taberu = NoteRequest(word = "食べる", reading = "たべる", sentence = "パンを食べる", origin = "takoboto", tags = listOf("anime"))

    @Test
    fun createMintsNoteCardsAndDeckItemInOrderWithValidPayloads() {
        val d = device()
        userDeck(d, deckId)
        val before = pending(d)
        var triggered = 0
        val r = assertIs<CreateResult.Created>(creator(d, afterCreate = { triggered++ }).create(taberu.copy(deckId = deckId)))

        assertEquals(listOf(OpType.NOTE_CREATED, OpType.CARD_CREATED, OpType.CARD_CREATED, OpType.DECK_ITEM_PUT), r.ops.map { it.opType })
        assertEquals(r.ops.sortedBy { it.hlc }, r.ops, "ascending hlc: the note sorts before what references it")
        for (op in r.ops) {
            assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(DefaultOpCodec.encodeTree(op), OpSource.PUSHED), op.opType.wire)
            assertEquals(emptyList(), DefaultOpCodec.envelopeErrors(op, OpSource.PUSHED))
        }
        val note = r.ops[0].payload.members
        assertEquals(JsonString(r.noteId), note["id"])
        assertEquals(JsonString(NoteCreator.VOCAB_NOTE_TYPE_ID), note["note_type_id"])
        assertEquals(JsonNumber(1358280.0), note["entry_seq"])
        assertEquals(JsonObject(mapOf("sentence" to JsonString("パンを食べる"))), note["fields"])
        assertEquals(listOf("anime"), stringList(note["tags"]))
        assertEquals(JsonString("パンを食べる"), note["source_sentence"])
        assertEquals(JsonString("takoboto"), note["origin"])
        assertEquals(listOf("meaning", "reading"), r.ops.subList(1, 3).map { (it.payload.members["kind"] as JsonString).value })
        assertEquals(r.cardIds, r.ops.subList(1, 3).map { it.entityId })
        r.ops.subList(1, 3).forEach { assertEquals(JsonString(r.noteId), it.payload.members["note_id"]) }
        val put = r.ops[3].payload.members
        assertEquals(JsonString("e:1358280"), put["item_key"])
        assertEquals(JsonNumber(1024.0), put["position"])

        // the local effect and the outbox, all in one commit
        assertEquals(before + 4, pending(d))
        assertEquals(1, triggered)
        val t = d.tables()
        val row = t.get(LearningTable.NOTE, listOf(JsonString(r.noteId)))!!
        assertEquals(JsonString("vocab"), row["type"])
        assertEquals(JsonNumber(1358280.0), row["entry_seq"])
        assertEquals(setOf("meaning", "reading"), cards(t, r.noteId).map { str(it, "kind") }.toSet())
        assertTrue(cards(t, r.noteId).all { str(it, "state") == "new" })
        assertEquals(1, t.where(LearningTable.DECK_ITEM, "deck_id", JsonString(deckId)).size)
        assertNotEquals(r.noteId, r.cardIds[0])
    }

    @Test
    fun aKillBeforeCommitLeavesNothing() {
        val d = device()
        val before = pending(d)
        val notesBefore = notes(d.tables()).size
        // the apply of the second op (the first card) dies: the note's apply already ran in the same transaction
        val dying = Stack(d.store, d.transport, d.clock, 99, applierWrap = { inner ->
            OpApplier { tx, op, origin ->
                if (op.opType == OpType.CARD_CREATED) throw Killed("while applying card.created")
                inner.apply(tx, op, origin)
            }
        })
        val c = NoteCreator<SqlLearningTables>(d.store, dying.minter, EntryMatcher(FakeSource(DICT)), UuidV7Source(Random(1)), d.clock)
        assertFailsWith<Killed> { c.create(taberu.copy(requestId = "req-1")) }
        d.restart()
        assertEquals(notesBefore, notes(d.tables()).size)
        assertEquals(0, d.tables().all(LearningTable.CARD).size)
        assertEquals(before, pending(d))
        assertNull(d.state(NoteCreator.REQUEST_KEY_PREFIX + "req-1"))
        // the retry after the restart creates it
        val r = assertIs<CreateResult.Created>(creator(d).create(taberu.copy(requestId = "req-1")))
        assertTrue(!r.retried)
        assertEquals(before + 3, pending(d))
    }

    @Test
    fun aRetriedRequestIdReturnsTheSameNoteAndMintsNothing() {
        val d = device()
        val first = assertIs<CreateResult.Created>(creator(d, seed = 1).create(taberu.copy(requestId = "req-7")))
        val after = pending(d)
        d.restart()
        d.clock.now += 60_000
        var triggered = 0
        val again = assertIs<CreateResult.Created>(creator(d, seed = 2, afterCreate = { triggered++ }).create(taberu.copy(requestId = "req-7")))
        assertTrue(again.retried)
        assertEquals(first.noteId, again.noteId)
        assertEquals(first.cardIds.toSet(), again.cardIds.toSet())
        assertEquals(emptyList(), again.ops)
        assertEquals(after, pending(d))
        assertEquals(0, triggered)
        // a different request id for the same word is a duplicate, not a retry
        assertIs<CreateResult.Duplicate>(creator(d, seed = 3).create(taberu.copy(requestId = "req-8")))
    }

    @Test
    fun aSecondAddOfTheSameWordIsReportedAndCreatesNothing() {
        val d = device()
        val first = assertIs<CreateResult.Created>(creator(d).create(taberu))
        val after = pending(d)
        val dup = assertIs<CreateResult.Duplicate>(creator(d, seed = 2).create(NoteRequest(word = "食べる")))
        assertEquals(first.noteId, dup.existing.noteId)
        assertEquals(1358280L, dup.existing.entrySeq)
        assertEquals(after, pending(d))
        // the kana form of the same entry is the same word
        assertIs<CreateResult.Duplicate>(creator(d, seed = 3).create(NoteRequest(word = "たべる", reading = "たべる")))
        // DuplicateCheck.find (T305c / T311) agrees
        assertEquals(first.noteId, duplicateCheck(d).find("食べる", "たべる")?.noteId)
        assertNull(duplicateCheck(d).find("猫", null))
    }

    @Test
    fun forceCreatesAKnowingDuplicateWithoutTheEntryReference() {
        val d = device()
        userDeck(d, deckId)
        val first = assertIs<CreateResult.Created>(creator(d).create(taberu))
        val forced = assertIs<CreateResult.Created>(creator(d, seed = 2).create(taberu.copy(force = true, deckId = deckId)))
        assertNotEquals(first.noteId, forced.noteId)
        val note = forced.ops.first { it.opType == OpType.NOTE_CREATED }.payload.members
        // SYNC.md 4: never a second live note of one entry_seq; the forced note carries the word in its fields
        assertNull(note["entry_seq"])
        val fields = (note["fields"] as JsonObject).members
        assertEquals(JsonString("食べる"), fields["expression"])
        assertEquals(JsonString("たべる"), fields["reading"])
        assertEquals(listOf("anime"), stringList(note["tags"]), "a forced note is not flagged unmatched")
        assertEquals(JsonString("n:${forced.noteId}"), forced.ops.last().payload.members["item_key"])
        val live = notes(d.tables()).filter { isLive(it) && it["entry_seq"] == JsonNumber(1358280.0) }
        assertEquals(listOf(first.noteId), live.map { str(it, "id") })
    }

    @Test
    fun noMatchCreatesADecklessNoteFlaggedForLaterMatching() {
        val d = device()
        userDeck(d, deckId)
        val r = assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "ぴえん", reading = "ぴえん", deckId = deckId)))
        assertEquals(MatchResult.NoMatch, r.match)
        assertEquals(listOf(OpType.NOTE_CREATED, OpType.CARD_CREATED, OpType.CARD_CREATED), r.ops.map { it.opType }, "no deck.item_put")
        val note = r.ops[0].payload.members
        assertNull(note["entry_seq"])
        assertEquals(listOf(NoteCreator.UNMATCHED_TAG), stringList(note["tags"]))
        assertEquals(JsonString("ぴえん"), (note["fields"] as JsonObject).members["expression"])
        // and it is found as a duplicate by its word
        assertIs<CreateResult.Duplicate>(creator(d, seed = 2).create(NoteRequest(word = "ぴえん")))
        // no dictionary installed at all behaves the same
        val none = creator(d, FakeSource(DICT, available = false), seed = 3).create(NoteRequest(word = "食べる", reading = "たべる"))
        assertEquals(MatchResult.NoMatch, assertIs<CreateResult.Created>(none).match)
    }

    @Test
    fun emptyFrontKindsAreSkipped() {
        val d = device()
        // listening: audio front, none given → skipped; production: meaning front, from the dictionary → kept
        userDeck(d, deckId, mapOf("card_kinds" to kotobaya.contracts.sync.JsonArray(listOf("listening", "production", "meaning").map(::JsonString))))
        val r = assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "猫", reading = "ねこ", deckId = deckId)))
        assertEquals(listOf("production", "meaning"), r.ops.filter { it.opType == OpType.CARD_CREATED }.map { (it.payload.members["kind"] as JsonString).value })
        // an unmatched word has no meaning anywhere: its production card is skipped too
        val u = assertIs<CreateResult.Created>(creator(d, seed = 2).create(NoteRequest(word = "ぴえん", deckId = deckId)))
        assertEquals(listOf("meaning"), u.ops.filter { it.opType == OpType.CARD_CREATED }.map { (it.payload.members["kind"] as JsonString).value })
        // given audio, listening is kept
        val a = assertIs<CreateResult.Created>(
            creator(d, seed = 3).create(NoteRequest(word = "食べ物", deckId = deckId, fields = mapOf("audio" to "[sound:${"a".repeat(64)}]"))),
        )
        assertEquals(3, a.cardIds.size)
    }

    @Test
    fun addKanjiCreatesMissingKanjiNotesBeforeTheWord() {
        val d = device()
        userDeck(d, deckId, mapOf("add_kanji" to JsonString("before")))
        val first = assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "食べる", reading = "たべる", deckId = deckId)))
        assertEquals(1, first.kanjiNoteIds.size)
        assertEquals(
            listOf(OpType.NOTE_CREATED, OpType.CARD_CREATED, OpType.NOTE_CREATED, OpType.CARD_CREATED, OpType.CARD_CREATED, OpType.DECK_ITEM_PUT),
            first.ops.map { it.opType },
        )
        val kanji = first.ops[0].payload.members
        assertEquals(JsonString("食"), kanji["kanji_literal"])
        assertEquals(JsonString("auto_kanji"), kanji["origin"])
        assertEquals(JsonString("keyword"), first.ops[1].payload.members["kind"])
        // 食 already has a live kanji note: only 物 is added
        val second = assertIs<CreateResult.Created>(creator(d, seed = 2).create(NoteRequest(word = "食べ物", deckId = deckId)))
        assertEquals(listOf(JsonString("物")), second.ops.filter { it.payload.members.containsKey("kanji_literal") }.map { it.payload.members["kanji_literal"] })
        assertEquals(JsonNumber(2048.0), second.ops.last().payload.members["position"])
    }

    @Test
    fun refusalsCreateNothing() {
        val d = device()
        val before = pending(d)
        assertEquals(CreateResult.Refused(CreateRefusal.NoWord), creator(d).create(NoteRequest(word = " ", reading = null)))
        assertEquals(
            CreateResult.Refused(CreateRefusal.DeckNotWritable(deckId)),
            creator(d).create(taberu.copy(deckId = deckId)),
        )
        val bad = assertIs<CreateResult.Refused>(creator(d).create(taberu.copy(tags = listOf("two words"))))
        assertIs<MintRefusal.Invalid>(assertIs<CreateRefusal.Mint>(bad.reason).refusal)
        d.store.transaction { it.syncState.remove(SyncStateKeys.DEVICE_ID) }
        assertEquals(CreateResult.Refused(CreateRefusal.Mint(MintRefusal.NotPaired)), creator(d).create(taberu))
        assertEquals(before, pending(d))
        assertEquals(0, notes(d.tables()).size)
    }
}
