package kotobaya.sync

import kotobaya.contracts.sync.OpType
import kotobaya.contracts.sync.OpType.*
import kotobaya.server.StartupSequence
import kotobaya.server.json
import org.jooq.exception.DataAccessException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Op apply on the server: idempotency, out-of-order delivery, append-only, review_log rules, the HLC, server-minted ops. */
class OpApplyTest : SyncFixture() {
    private val note1 = "0193a1b2-0001-7000-8000-000000000001"
    private val card1 = "0193a1b2-0002-7000-8000-000000000001"
    private val rev1 = "0193a1b2-0003-7000-8000-000000000001"
    private val base = 0x0193a1b2c000L

    private fun noteOp(hlc: Long = base + 1, id: String = note1, seq: Long = 1358280) =
        op(NOTE_CREATED, A, hlc, """{"id":"$id","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":$seq,"fields":{"meaning":"to eat","notes":""}}""")

    private fun cardOp(hlc: Long = base + 2, id: String = card1, note: String = note1) =
        op(CARD_CREATED, A, hlc, """{"id":"$id","note_id":"$note","kind":"meaning"}""")

    private fun reviewOp(hlc: Long, id: String = rev1, device: String = A, reviewedAt: Long = hlc, type: String = "scheduled", rating: Int = 3) =
        op(REVIEW_LOGGED, device, hlc, """{"id":"$id","card_id":"$card1","reviewed_at":$reviewedAt,"utc_offset_min":540,"review_type":"$type","rating":$rating}""")

    private fun undoOp(hlc: Long, device: String = A, review: String = rev1, ctr: Int = 0) = op(REVIEW_UNDONE, device, hlc, """{"review_id":"$review"}""", ctr)

    private fun snapshot(): String = db.txBlocking { dsl ->
        listOf("note", "card", "review_log", "settings", "word_status", "deck", "deck_item").joinToString("\n") { t ->
            dsl.fetch("select to_jsonb(t)::text from app.$t t order by 1").joinToString { it.get(0, String::class.java) }
        }
    }

    // ------------------------------------------------------------------ idempotency, out of order

    @Test
    fun rePushingABatchAppliesNothingTwiceAndAnswersDuplicateWithTheOriginalSeqs() {
        val a = principal(A)
        val batch = arrayOf(noteOp(), cardOp(), reviewOp(base + 10), op(CARD_SUSPENDED, A, base + 11, """{"id":"$card1"}"""))
        val first = push(a, *batch)
        first.forEach(::assertAccepted)
        val before = snapshot()
        val changes = count("select count(*) from app.change")
        val again = push(a, *batch)
        assertEquals(first.map { PushResult(it.opId, PushResult.DUPLICATE, it.seq) }, again)
        assertEquals(changes, count("select count(*) from app.change"))
        assertEquals(before, snapshot())
        assertEquals(1, cache.calls.count { it.startsWith("logged") }, "the review was folded once")
    }

    @Test
    fun anOpBeforeItsCreatorIsRejectedUnknownReferenceAndAcceptedOnceTheCreatorArrived() {
        val a = principal(A)
        val res = push(a, cardOp(), noteOp())
        assertRejected(RejectCode.UNKNOWN_REFERENCE, res[0])
        assertAccepted(res[1])
        assertNull(row("card", "id" to card1))
        assertAccepted(push(a, cardOp()).single()) // the client's Retry (SYNC.md 10.2)
        assertNotNull(row("card", "id" to card1))
    }

    @Test
    fun aTombstoneBeforeAnOlderEditKeepsTheRowDeletedAndStillTakesTheEdit() {
        push(principal(A), noteOp()).forEach(::assertAccepted)
        assertAccepted(push(principal(B), op(NOTE_DELETED, B, base + 20, """{"id":"$note1"}""")).single())
        assertAccepted(push(principal(A), op(NOTE_UPDATED, A, base + 10, """{"id":"$note1","set":{"tags":["anime"]}}""")).single())
        val n = row("note", "id" to note1)!!
        assertEquals(base + 20, SyncJson.ms(n["deleted_at"]))
        assertEquals(listOf("anime"), n["tags"].map { it.asText() })
        assertEquals(base + 20, SyncJson.ms(n["updated_at"]))
        // An older delete loses the register: deleted_at stays at the highest delete clock.
        assertAccepted(push(principal(A), op(NOTE_DELETED, A, base + 15, """{"id":"$note1"}""")).single())
        assertEquals(base + 20, SyncJson.ms(row("note", "id" to note1)!!["deleted_at"]))
    }

    @Test
    fun creationRuleStampsOnlyNonDefaultRegistersAndDropsEmptyFields() {
        push(principal(A), noteOp(), cardOp()).forEach(::assertAccepted)
        val n = row("note", "id" to note1)!!
        assertEquals(json.readTree("""{"meaning":"to eat"}"""), n["fields"])
        assertEquals(setOf("entry_seq", "fields.meaning"), n["clocks"].fieldNames().asSequence().toSet())
        assertEquals("vocab", n["type"].asText())
        assertEquals(A, n["created_by_device_id"].asText())
        assertEquals(json.readTree("{}"), row("card", "id" to card1)!!["clocks"])
        // A second note.created with the same id is a no-op, whatever it holds.
        val again = op(NOTE_CREATED, B, base + 30, """{"id":"$note1","note_type_id":"00000000-0000-7000-8000-000000000101","mnemonic":"other"}""")
        assertAccepted(push(principal(B), again).single())
        assertEquals("", row("note", "id" to note1)!!["mnemonic"].asText())
    }

    // ------------------------------------------------------------------ review_log (SYNC.md 7.9)

    @Test
    fun reviewLogIsAppendOnlyAndTheEarliestUndoWins() {
        val a = principal(A)
        push(a, noteOp(), cardOp(), reviewOp(base + 10)).forEach(::assertAccepted)
        // Duplicate insert with different content keeps the first.
        assertAccepted(push(principal(B), reviewOp(base + 11, device = B, rating = 1)).single())
        assertEquals(3, row("review_log", "id" to rev1)!!["rating"].asInt())
        // Undo A(h=20) then B(h=15) -> 15.
        assertAccepted(push(a, undoOp(base + 20)).single())
        val undoB = undoOp(base + 15, device = B)
        assertAccepted(push(principal(B), undoB).single())
        val r = row("review_log", "id" to rev1)!!
        assertEquals("%012x-0000-%s".format(base + 15, B), r["undone_hlc"].asText())
        assertEquals(base + 15, SyncJson.ms(r["undone_at"]))
        // The same undo again, and a later one: unchanged.
        assertEquals(PushResult.DUPLICATE, push(principal(B), undoB).single().status)
        assertAccepted(push(principal(B), undoOp(base + 15, device = B, ctr = 1)).single()) // a later undo: accepted, no effect
        assertAccepted(push(a, undoOp(base + 25)).single())
        assertEquals("%012x-0000-%s".format(base + 15, B), row("review_log", "id" to rev1)!!["undone_hlc"].asText())
        // Undo for an unknown review: the server rejects it.
        assertRejected(RejectCode.UNKNOWN_REFERENCE, push(a, undoOp(base + 30, review = "0193a1b2-0003-7000-8000-0000000000ff")).single())
        // Only the first transition to undone forces a replay.
        assertEquals(1, cache.calls.count { it.startsWith("undone") })
    }

    @Test
    fun undoOrderBThenAAlsoKeepsTheEarliest() {
        push(principal(A), noteOp(), cardOp(), reviewOp(base + 10)).forEach(::assertAccepted)
        assertAccepted(push(principal(B), undoOp(base + 15, device = B)).single())
        assertAccepted(push(principal(A), undoOp(base + 20)).single())
        assertEquals("%012x-0000-%s".format(base + 15, B), row("review_log", "id" to rev1)!!["undone_hlc"].asText())
    }

    @Test
    fun updatingOrDeletingChangeOrReviewLogIsRefusedByTheApplicationAndByTheTrigger() {
        push(principal(A), noteOp(), cardOp(), reviewOp(base + 10)).forEach(::assertAccepted)
        val forbidden = listOf(
            "update app.change set entity_id = 'x'",
            "delete from app.change",
            "UPDATE app.review_log SET rating = 1",
            "delete from app.review_log where id = '$rev1'",
            "update app.review_log set undone_hlc = null",
        )
        for (sql in forbidden) {
            // Application layer: the op transaction's DSL refuses before Postgres sees it.
            assertFailsWith<AppendOnlyViolation>(sql) { sync.opApply.transactionBlocking { it.dsl.execute(sql) } }
            // Database layer: the trigger refuses a raw connection.
            assertFailsWith<DataAccessException>(sql) { db.txBlocking { it.execute(sql) } }
        }
        assertEquals(3, count("select count(*) from app.change"))
        assertEquals(3, row("review_log", "id" to rev1)!!["rating"].asInt())
        // The one allowed statement still works through the guard.
        assertAccepted(push(principal(A), undoOp(base + 20)).single())
    }

    // ------------------------------------------------------------------ the server HLC

    @Test
    fun hlcLastIsPersistedObservedAndSurvivesARestart() {
        val ahead = base + 3_600_000 // a phone one hour ahead of the server clock
        syncNow.set(Instant.ofEpochMilli(base))
        assertAccepted(push(principal(A), op(SETTINGS_SET, A, ahead, """{"key":"a.b","value":1}""")).single())
        val stored = db.txBlocking { it.fetchValue("select value from app.server_state where key = 'hlc_last'") as String }
        assertEquals("%012x-0000-%s".format(ahead, SERVER), stored, "observe(h) persisted with the op")
        // "Restart": a new module on the same database.
        val restarted = SyncModule(db, syncConfig())
        val minted = restarted.opApply.transactionBlocking { it.emit(SETTINGS_SET, obj("""{"key":"c.d","value":2}""")) }
        assertTrue(minted.op.hlc > "%012x-0000-%s".format(ahead, A), "server mints above everything it observed: ${minted.op.hlc}")
        assertEquals(SERVER, minted.op.deviceId)
        assertEquals("%012x-0001-%s".format(ahead, SERVER), minted.op.hlc)
    }

    @Test
    fun clockBoundsOfSection3_4() {
        val now = base
        fun at(ms: Long) = kotobaya.contracts.sync.Op("x", SETTINGS_SET, 1, "settings", "k", A, "%012x-0000-%s".format(ms, A), obj("""{"key":"k","value":1}"""))
        assertNull(PushService.clockCheck(at(now + 86_400_000 + 60_000), now))
        assertEquals(RejectCode.CLOCK_AHEAD, PushService.clockCheck(at(now + 86_400_000 + 60_001), now)?.code)
        assertNull(PushService.clockCheck(at(1_577_836_800_000), now))
        assertEquals(RejectCode.CLOCK_BEHIND, PushService.clockCheck(at(1_577_836_799_999), now)?.code)
    }

    // ------------------------------------------------------------------ server-minted ops (OpApply)

    @Test
    fun serverMintedOpsFailingAPatternAre422BeforeAnythingIsMinted() {
        val changes = count("select count(*) from app.change")
        for (tag in listOf("a　b", "a b", "﻿a")) {
            val e = assertFailsWith<OpInputInvalid> {
                emit(NOTE_CREATED, """{"id":"$note1","note_type_id":"00000000-0000-7000-8000-000000000101","tags":["$tag"]}""")
            }
            val api = e.toApiException()
            assertEquals(422, api.status.value)
            assertEquals("validation_failed", api.code)
            @Suppress("UNCHECKED_CAST")
            val err = (api.extra["errors"] as List<Map<String, Any?>>).single()
            assertEquals("invalid_value", err["code"])
            assertTrue((err["pointer"] as String).startsWith("/tags"), err.toString())
        }
        assertFailsWith<OpInputInvalid> { emit(SETTINGS_SET, """{"key":"a.b\n","value":1}""") }
        // Deeper than MAX_OP_DEPTH (500): never minted.
        val deep = "[".repeat(499) + "1" + "]".repeat(499) // envelope 1 + payload 1 + 499 arrays = 501
        assertFailsWith<OpInputInvalid> { emit(SETTINGS_SET, """{"key":"a.b","value":$deep}""") }
        // Exactly MAX_OP_DEPTH (envelope 1 + payload 1 + 498 arrays = 500) is fine.
        val ok = "[".repeat(498) + "1" + "]".repeat(498)
        emit(SETTINGS_SET, """{"key":"a.b","value":$ok}""")
        assertEquals(changes + 1, count("select count(*) from app.change"))
        // The dry run reports without minting.
        val dry = sync.opApply.transactionBlocking { it.check(NOTE_CREATED, obj("""{"id":"$note1","note_type_id":"00000000-0000-7000-8000-000000000101","tags":["a b"]}""")) }
        assertNotNull(dry)
    }

    @Test
    fun serverMintedOpsAreRefusedForMissingReferencesAndImmutables() {
        assertEquals(RejectCode.UNKNOWN_REFERENCE, assertFailsWith<OpRefused> { emit(CARD_SUSPENDED, """{"id":"$card1"}""") }.rejection.code)
        val e = assertFailsWith<OpRefused> { emit(FSRS_PARAMS_DELETED, """{"id":"00000000-0000-7000-8000-000000000010"}""") }
        assertEquals(RejectCode.IMMUTABLE, e.rejection.code)
        assertEquals(409, e.toApiException().status.value)
        assertEquals(0, count("select count(*) from app.change"))
    }

    @Test
    fun aRestStyleTransactionLogsSeveralOpsWithTheTokenAndReportsTheLastSeq() {
        val token = UUID.randomUUID()
        val last = sync.opApply.transactionBlocking(token) { tx ->
            tx.emit(NOTE_CREATED, obj("""{"id":"$note1","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":1}"""))
            tx.emit(CARD_CREATED, obj("""{"id":"$card1","note_id":"$note1","kind":"meaning"}"""))
            tx.lastSeq
        }
        assertEquals(2L, last)
        assertEquals(2, count("select count(*) from app.change where token_id = ?::uuid and device_id = ?::uuid", token, SERVER))
    }

    // ------------------------------------------------------------------ every op type

    /**
     * Every example op of `contracts/sync/examples/ops`, in HLC order: client ops pushed by their
     * device, server ops minted by the server. The four duplicate/merge examples need T202c's
     * collision handling and are covered by [mergeAppliesSection8_2AndForwardsLaterWrites] instead.
     */
    @Test
    fun everyExampleOpTypeAppliesInOrder() {
        val skip = setOf("note.created.duplicate", "card.created.duplicate", "note.merged", "card.merged", "deck.deleted.cascade")
        val dir = java.io.File(contracts, "examples/ops")
        val ops = dir.listFiles()!!.map { it.name.removeSuffix(".json") }.filter { it !in skip }
            .map { it to example(it) }.sortedBy { it.second["hlc"].asText() }
        val types = mutableSetOf<String>()
        for ((name, o) in ops) {
            if (o["device_id"].asText() == SERVER) {
                syncNow.set(Instant.ofEpochMilli(wall(o["hlc"].asText())))
                val logged = emit(OpType.entries.first { it.wire == o["op_type"].asText() }, json.writeValueAsString(o["payload"]))
                assertEquals(o["op_type"].asText(), logged.op.opType.wire, name)
            } else {
                assertAccepted(pushAsOwner(o))
            }
            types += o["op_type"].asText()
        }
        assertEquals(45 - 2, types.size, "every op type except the merges: $types")
        // media.deleted cascaded to the media deck, logged right after it with caused_by_op_id.
        val mediaDeleted = example("media.deleted")["op_id"].asText()
        val cascade = db.txBlocking {
            it.fetchOne("select op_type, caused_by_op_id::text, device_id::text from app.change where caused_by_op_id is not null")!!
        }
        assertEquals(listOf("deck.deleted", mediaDeleted, SERVER), listOf(cascade.get(0), cascade.get(1), cascade.get(2)))
        assertNotNull(row("deck", "id" to "0193a1b2-0007-7000-8000-000000000001")!!["deleted_at"].textValue())
        // A few end states.
        val deck = row("deck", "id" to "0193a1b2-0004-7000-8000-000000000001")!!
        assertEquals("Mined", deck["name"].asText())
        assertEquals(json.readTree("""{"add_kanji":"before"}"""), deck["settings"])
        assertTrue(deck["clocks"].has("settings.order"), "removing a key keeps its clock")
        val item = row("deck_item", "deck_id" to "0193a1b2-0004-7000-8000-000000000001", "item_key" to "e:1358280")!!
        assertNotNull(item["deleted_at"].textValue())
        val preset = row("fsrs_params", "id" to "0193a1b2-0006-7000-8000-000000000001")!!
        assertEquals(1, preset["weights_version"].asInt())
        assertEquals(0.95, preset["desired_retention"].asDouble())
        assertNotNull(preset["deleted_at"].textValue())
        val confusables = db.txBlocking { it.fetch("select reason, status from app.confusable order by reason").map { r -> "${r.get(0)}=${r.get(1)}" } }
        assertEquals(listOf("homophone=rejected", "meaning=confirmed"), confusables)
        val ws = row("word_status", "entry_seq" to 1411050L)!!
        assertTrue(ws["override_status"].isNull)
        assertEquals("new", ws["status"].asText())
        val p = "0193a1b2-0006-7000-8000-000000000001"
        assertEquals(
            listOf(
                "preset $p UPDATED", "preset $p PUBLISHED", "replay [$card1]",
                "logged $rev1 -> $card1", "logged 0193a1b2-0003-7000-8000-000000000002 -> $card1",
                "logged 0193a1b2-0003-7000-8000-000000000003 -> $card1", "undone $rev1 -> $card1", "preset $p DELETED",
            ),
            cache.calls.toList(),
        )
    }

    @Test
    fun mergeAppliesSection8_2AndForwardsLaterWrites() {
        val a = principal(A)
        val loser = "0193a1b2-0001-7000-8000-00000000000a"
        val loserCard = "0193a1b2-0002-7000-8000-00000000000a"
        push(a, noteOp(), cardOp()).forEach(::assertAccepted)
        push(
            a,
            op(NOTE_CREATED, A, base + 3, """{"id":"$loser","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":42,"fields":{"sentence":"パンを食べる"},"mnemonic":"x"}"""),
            op(CARD_CREATED, A, base + 4, """{"id":"$loserCard","note_id":"$loser","kind":"meaning","flags":2}"""),
        ).forEach(::assertAccepted)
        syncNow.set(Instant.ofEpochMilli(base + 100))
        emit(NOTE_MERGED, """{"loser_id":"$loser","survivor_id":"$note1"}""")
        emit(CARD_MERGED, """{"loser_id":"$loserCard","survivor_id":"$card1"}""")
        val w = row("note", "id" to note1)!!
        assertEquals("パンを食べる", w["fields"]["sentence"].asText())
        assertEquals("x", w["mnemonic"].asText())
        assertEquals(1358280, w["entry_seq"].asLong(), "entry_seq is never forwarded")
        val l = row("note", "id" to loser)!!
        assertEquals(note1, l["merged_into_id"].asText())
        assertEquals(base + 100, SyncJson.ms(l["deleted_at"]))
        assertTrue(!l["clocks"].has("deleted"))
        assertEquals(2, row("card", "id" to card1)!!["flags"].asInt())
        assertTrue(cache.calls.contains("replay [$card1]"))
        // 8.3: a later write addressed to the loser card reaches the survivor too.
        assertAccepted(push(a, op(CARD_SUSPENDED, A, base + 5, """{"id":"$loserCard"}""")).single())
        assertEquals(true, row("card", "id" to card1)!!["suspended"].asBoolean())
        // A merge whose loser is already merged is a no-op.
        val before = snapshot()
        emit(NOTE_MERGED, """{"loser_id":"$loser","survivor_id":"$note1"}""")
        assertEquals(before, snapshot())
    }

    @Test
    fun startupCreatesTheEpochOnceAndPushNeedsIt() {
        val epoch = { db.txBlocking { it.fetchValue("select value from app.server_state where key = 'log_epoch'") as String? } }
        sync // creates it
        val e1 = epoch()
        StartupSequence(db).runOnce()
        assertEquals(e1, epoch())
    }
}
