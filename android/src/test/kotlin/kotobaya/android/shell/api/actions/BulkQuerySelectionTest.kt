package kotobaya.android.shell.api.actions

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.OfflineUpstream
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.bulk
import kotobaya.android.shell.api.actions.ActionsTestSupport.card
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.actions.routes as actionRoutes
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonString
import kotobaya.db.app.AppDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `query` selector of `bulkCards` selects exactly what the server's `CardSearch.compile` would, or is forwarded:
 * live smart decks resolve `deck:` terms (also negated), a dictionary-form lookup the device does not trust in full is
 * never acted on locally, and `bulk_too_large` is judged on the live cards. Robolectric: the C-24 dictionary forms
 * read the installed package through Android SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class BulkQuerySelectionTest {
    private val leeches = "0193c000-0d00-7000-8000-0000000000aa"

    /** A smart deck "Leeches" (`tag:lee`) holding note 1's card only. */
    private fun withSmartDeck(): ParityDevice {
        val d = device()
        d.clock.now = T0 + 500
        d.pull(
            listOf(
                remote(1, "note.updated", "note", N1, T0, obj("id" to JsonString(N1), "set" to obj("tags" to JsonArray(listOf(JsonString("lee")))))),
                remote(
                    2, "deck.created", "deck", leeches, T0 + 1,
                    obj("id" to JsonString(leeches), "name" to JsonString("Leeches"), "type" to JsonString("smart"), "query" to JsonString("tag:lee")),
                ),
            ),
            "a smart deck",
        )
        return d
    }

    @Test
    fun aNegatedSmartDeckTermSelectsTheCardsOutsideTheDeck() {
        val d = withSmartDeck()
        val res = d.bulk("""{"query":"-deck:\"Leeches\"","action":{"type":"suspend"}}""", at = T0 + 1_000)
        assertEquals(200, res.status, res.body)
        assertEquals(1L, ParityJson.long(res.json().members["matched"]))
        assertEquals(listOf(C2), d.minted().map { ParityJson.str(it.members["entity_id"]) })
        assertEquals(JsonBool(false), d.card(C1)["suspended"])
        assertEquals(JsonBool(true), d.card(C2)["suspended"])
    }

    @Test
    fun aSmartDeckTermSelectsTheDecksCards() {
        val d = withSmartDeck()
        val res = d.bulk("""{"query":"deck:Leeches","action":{"type":"flag","flags":2},"dry_run":true}""", at = T0 + 1_000)
        assertEquals(1L, ParityJson.long(res.json().members["matched"]))
    }

    /** Services of [d] with [query] as the selector (the handler's real dependencies otherwise). */
    private fun api(d: ParityDevice, query: CardQuery, forwarded: MutableList<String>): LocalApi = LocalApi(
        routes = actionRoutes,
        gate = object : ShellGate {
            override fun isPaired(): Boolean = true
            override fun isRebuildPending(): Boolean = false
            override suspend fun awaitReady() = Unit
        },
        upstream = Upstream { r, op, id -> forwarded += op.operationId; OfflineUpstream.forward(r, op, id) },
        services = object : ShellServices {
            private val byType: Map<KClass<*>, Any> = mapOf(
                ShellIdentity::class to d.identity, CardQuery::class to query, LocalWrites::class to d.writes, AppDatabase::class to d.database,
            )

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> get(type: KClass<T>): T = byType[type] as T? ?: error("no ${type.simpleName}")
        },
        newRequestId = { "r" },
    )

    private fun LocalApi.bulk(body: String): ShellResponse = runBlocking {
        handle(ShellRequest.fromRaw("POST", "/api/v1/cards/bulk", "", mapOf("content-type" to "application/json"), body))
    }

    /** A text term whose dictionary forms exceed the device's guard is forwarded, never applied to a partial set. */
    @Test
    fun aTooBroadDictionaryFormLookupIsForwardedNotApplied() {
        val d = device()
        d.clock.now = T0 + 1_000
        val trusted = LocalCardQuery(d.driver, d.database, d.installedDictionary)
        val selected = trusted.select("うっかり", T0 + 1_000)
        assertEquals(CardQueryResult.Ids(listOf(C1)), selected, "the dictionary form finds note 1 (its fields are empty)")
        val guarded = LocalCardQuery(d.driver, d.database, d.installedDictionary, dictFormsMaxSeqs = 0)
        assertTrue(guarded.select("うっかり", T0 + 1_000) is CardQueryResult.Forward)

        val forwarded = ArrayList<String>()
        val res = api(d, guarded, forwarded).bulk("""{"query":"うっかり","action":{"type":"suspend"}}""")
        assertEquals("503 shell_offline", res.problem())
        assertEquals(listOf("bulkCards"), forwarded)
        assertEquals(0, d.minted().size)
    }

    /** `bulk_too_large` counts the live cards: ids of tombstoned cards a selector returns do not count. */
    @Test
    fun bulkTooLargeIsJudgedOnLiveCards() {
        val d = device()
        d.clock.now = T0 + 1_000
        val dead = (1..5_000).map { "0193d000-0000-7000-8000-%012x".format(it) }
        val forwarded = ArrayList<String>()
        val res = api(d, { _, _ -> CardQueryResult.Ids((dead + C1).sorted()) }, forwarded).bulk("""{"query":"x","action":{"type":"suspend"}}""")
        assertEquals(200, res.status, res.body)
        assertEquals(1L, ParityJson.long(res.json().members["matched"]))
        // and a real selection over the replica never returns tombstoned cards
        d.pull(listOf(remote(9, "card.deleted", "card", C2, T0 + 1_500, obj("id" to JsonString(C2)))), "card 2 deleted")
        assertEquals(CardQueryResult.Ids(listOf(C1)), LocalCardQuery(d.driver, d.database, d.installedDictionary).select("kind:meaning", T0 + 2_000))
    }
}
