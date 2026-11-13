package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** SYNC.md 8 on devices: merges applied locally, loser edits forwarded, readers resolve losers (8.5). */
class MergeTest {
    private val a = "01931f3e-0000-7000-8000-0000000000a1"
    private val b = "01931f3e-0000-7000-8000-0000000000b2"

    private fun note(t: LearningTables, id: String): Row = t.get(LearningTable.NOTE, listOf(JsonString(id)))!!

    @Test
    fun theSameWordAddedOnTwoDevicesEndsIdenticalOnBothReplicas() {
        val name = "same-word-added-twice.json"
        val play = ScenarioPlay(name)
        play.play(1L..3L)
        val devA = play.device(a)
        val devB = play.device(b)
        assertIs<CycleResult.Completed>(devA.cycle())
        // B is offline: its whole duplicate (note, cards, tag, suspend, review) exists only locally
        for (seq in listOf(4L, 6L, 8L, 11L, 12L, 13L)) devB.inject(play.log.single { it.seq == seq })
        val loser = "0193a1b2-0001-7000-8000-000000000002"
        assertEquals(JsonString("anime"), (note(devB.tables(), loser)["tags"] as kotobaya.contracts.sync.JsonArray).items.single())
        assertIs<CycleResult.Completed>(kotobaya.sync.client.protocol.runSync { devB.engine.pushAll() })
        play.play(14L..14L)
        play.assertServerLogIsTheScenarioLog()
        // both pull: B merges locally, forwards its edits and joins the review sets
        assertIs<CycleResult.Completed>(devB.cycle())
        assertIs<CycleResult.Completed>(devA.cycle())
        val server = serverReplica(play.server)
        for (d in listOf(devA, devB)) {
            assertScenarioExpected(name, d.tables(), d.cache, "device ${d.deviceId.takeLast(2)}")
            assertEquals(projection(server), projection(d.tables()), "device ${d.deviceId.takeLast(2)} vs server")
            assertEquals(emptyList(), d.diagnostics.refusals)
        }
        // identical on both replicas (SYNC.md 11: a loser's unmaintained cache may differ: B folded into it before the merge)
        assertEquals(projection(devA.tables()), projection(devB.tables()))
        val meaning = "0193a1b2-0002-7000-8000-000000000001"
        val card = devB.tables().get(LearningTable.CARD, listOf(JsonString(meaning)))!!
        assertEquals(JsonString("0193a1b2-0003-7000-8000-000000000002"), card["last_review_id"], "the merged replay set ends with A's review")
    }

    // ------------------------------------------------------------- jpdb import vs offline phone add

    private val t0 = 1_733_584_666_000L

    private fun hlc(ms: Long, device: String, counter: Int = 0): String =
        (t0 + ms).toString(16).padStart(12, '0') + "-" + counter.toString(16).padStart(4, '0') + "-" + device

    private fun op(id: String, type: OpType, entityId: String, hlc: String, payload: Map<String, JsonValue>): Op =
        Op(id, type, 1, type.entityType, entityId, hlc.takeLast(36), hlc, JsonObject(payload))

    @Test
    fun aJpdbImportKanjiDedupeAgainstAConcurrentOfflinePhoneAddGoesThroughTheMerge() {
        val s = Reserved.SERVER_DEVICE_ID
        val w = "0193a1b2-0001-7000-8000-0000000000f1"
        val wCard = "0193a1b2-0002-7000-8000-0000000000f1"
        val l = "0193a1b2-0001-7000-8000-0000000000f2"
        val lCard = "0193a1b2-0002-7000-8000-0000000000f2"
        val review = "0193a1b2-0003-7000-8000-0000000000f2"
        val importNote = op(
            "0193a1b2-00ff-7000-8000-0000000f0001", OpType.NOTE_CREATED, w, hlc(1, s),
            mapOf(
                "id" to JsonString(w), "note_type_id" to JsonString(Reserved.NOTE_TYPE_KANJI), "kanji_literal" to JsonString("食"),
                "fields" to JsonObject(mapOf("keyword" to JsonString("eat"))), "origin" to JsonString("jpdb_import"),
            ),
        )
        val importCard = op(
            "0193a1b2-00ff-7000-8000-0000000f0002", OpType.CARD_CREATED, wCard, hlc(1, s, 1),
            mapOf("id" to JsonString(wCard), "note_id" to JsonString(w), "kind" to JsonString("keyword")),
        )
        val phoneNote = op(
            "0193a1b2-00ff-7000-8000-0000000f0003", OpType.NOTE_CREATED, l, hlc(5, a),
            mapOf(
                "id" to JsonString(l), "note_type_id" to JsonString(Reserved.NOTE_TYPE_KANJI), "kanji_literal" to JsonString("食"),
                "fields" to JsonObject(mapOf("keyword" to JsonString("meal"))),
            ),
        )
        val phoneCard = op(
            "0193a1b2-00ff-7000-8000-0000000f0004", OpType.CARD_CREATED, lCard, hlc(6, a),
            mapOf("id" to JsonString(lCard), "note_id" to JsonString(l), "kind" to JsonString("keyword")),
        )
        val phoneMnemonic = op(
            "0193a1b2-00ff-7000-8000-0000000f0005", OpType.NOTE_UPDATED, l, hlc(7, a),
            mapOf("id" to JsonString(l), "set" to JsonObject(mapOf("mnemonic" to JsonString("a meal to eat")))),
        )
        val example = Examples.op("review.logged.json").payload.members
        val phoneReview = op(
            "0193a1b2-00ff-7000-8000-0000000f0006", OpType.REVIEW_LOGGED, review, hlc(8, a),
            example + mapOf("id" to JsonString(review), "card_id" to JsonString(lCard), "reviewed_at" to JsonNumber((t0 + 8).toDouble())),
        )
        val noteMerged = op(
            "0193a1b2-00ff-7000-8000-0000000f0007", OpType.NOTE_MERGED, l, hlc(100, s),
            mapOf("loser_id" to JsonString(l), "survivor_id" to JsonString(w)),
        )
        val cardMerged = op(
            "0193a1b2-00ff-7000-8000-0000000f0008", OpType.CARD_MERGED, lCard, hlc(101, s),
            mapOf("loser_id" to JsonString(lCard), "survivor_id" to JsonString(wCard)),
        )
        val server = TestServer(t0 + 1_000)
        server.emitAfter = { o ->
            when (o.opId) {
                phoneNote.opId -> listOf(noteMerged)
                phoneCard.opId -> listOf(cardMerged)
                else -> emptyList()
            }
        }
        val phone = ApplyDevice(a, server, t0 + 1_000)
        val other = ApplyDevice(b, server, t0 + 1_000, seed = 2)
        // the phone adds the word offline while the import job runs on the server
        listOf(phoneNote, phoneCard, phoneMnemonic, phoneReview).forEach { phone.inject(it) }
        server.append(importNote, null)
        server.append(importCard, null)
        assertIs<CycleResult.Completed>(phone.cycle())
        assertIs<CycleResult.Completed>(other.cycle())

        val serverTables = serverReplica(server)
        for (d in listOf(phone, other)) {
            val t = d.tables()
            assertEquals(projection(serverTables), projection(t))
            val loser = note(t, l)
            assertEquals(JsonString(w), loser["merged_into_id"])
            assertEquals(JsonNumber(DefaultHlcRules.wall(noteMerged.hlc).toDouble()), loser["deleted_at"])
            val survivor = note(t, w)
            assertEquals(JsonObject(mapOf("keyword" to JsonString("meal"))), survivor["fields"], "the phone's newer keyword wins on W")
            assertEquals(JsonString("a meal to eat"), survivor["mnemonic"], "forwarded")
            assertEquals(JsonString(wCard), t.get(LearningTable.CARD, listOf(JsonString(lCard)))!!["merged_into_id"])
            assertEquals(listOf(review), d.cache.replaySet(t, wCard).map { it.id })
            assertEquals(JsonString(review), t.get(LearningTable.CARD, listOf(JsonString(wCard)))!!["last_review_id"])
            assertEquals(listOf(w), t.all(LearningTable.NOTE).filter { (it["deleted_at"] as? JsonNumber) == null }.map { (it["id"] as JsonString).value })
        }
    }

    // ------------------------------------------------------------- readers (8.5)

    @Test
    fun readersResolveDeckItemsAndConfusablesThroughMergedIntoId() {
        val t = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        val log = scenarioLog("duplicate-kanji-deck-item-confusable.json")
        log.forEach { applier.applyTo(t, it) }
        val deck = "0193a1b2-0004-7000-8000-000000000001"
        val w = "0193a1b2-0001-7000-8000-000000000001"
        val l = "0193a1b2-0001-7000-8000-000000000002"
        val hold = "0193a1b2-0001-7000-8000-000000000003"
        assertEquals(w, (MergeReaders.resolveNote(t, l)!!["id"] as JsonString).value)
        assertEquals(w, (MergeReaders.resolveNote(t, w)!!["id"] as JsonString).value)
        val loserItem = t.get(LearningTable.DECK_ITEM, listOf(JsonString(deck), JsonString("n:$l")))!!
        assertEquals("n:$w", MergeReaders.effectiveIdentity(t, loserItem), "deck_item n:<loser> follows merged_into_id")
        val visible = MergeReaders.visibleDeckItems(t, deck)
        assertEquals(listOf("n:$l"), visible.map { (it["item_key"] as JsonString).value }, "one item: the smallest (position, item_key)")
        assertEquals(listOf("n:$w", "n:$l"), MergeReaders.keysToRemove(t, deck, "n:$l"))
        assertEquals(
            listOf(MergeReaders.ConfusablePair(minOf(w, hold), maxOf(w, hold), "look_alike", "confirmed")),
            MergeReaders.confusablePairs(t),
        )
        // the survivor deleted: the item and the pair are hidden
        val del = Op(
            "0193a1b2-00ff-7000-8000-0000000f00d1", OpType.NOTE_DELETED, 1, "note", w, b,
            later(log.last().hlc, 1_000).let { it.substring(0, 18) + b },
            JsonObject(mapOf("id" to JsonString(w))),
        )
        applier.applyTo(t, del)
        assertEquals(emptyList(), MergeReaders.visibleDeckItems(t, deck))
        assertEquals(emptyList(), MergeReaders.confusablePairs(t))
        assertEquals(null, MergeReaders.resolveCard(t, "0193a1b2-0002-7000-8000-00000000ffff"))
    }
}
