package kotobaya.jobs

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotobaya.server.ApiSpec
import kotobaya.server.AppDeps
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C-9: server-internal job kinds (`jpdb-history-apply`) never leave the server through the API, SSE or ntfy. */
class InternalJobsTest {
    @Test
    fun apiJobKindsEqualTheOpenApiJobKindEnum() {
        val spec = ApiSpec.load()
        val kinds = spec.root.path("components").path("schemas").path("JobKind").path("enum").map { it.asText() }
        assertTrue(kinds.isNotEmpty())
        assertEquals(kinds, API_JOB_KINDS)
    }

    @Test
    fun anInternalJobIsInvisibleToListGetCancelAndTheEventWatcher() = testApplication {
        val db = TestPg.migratedDb()
        val jobs = JobsModule(db, "unused")
        application { kotobayaModule(AppDeps(db, handlers = jobs.handlers)) }
        val internal = Uuid7.generate()
        val public = Uuid7.generate()
        fun insert(id: java.util.UUID, kind: String) = db.txBlocking {
            it.execute("insert into app.job (id, kind, payload, status, started_at) values (?, ?, '{}'::jsonb, 'running', now())", id, kind)
        }

        // The watcher is baselined first, so what it sees next is "new job" events.
        val sub = jobs.hub.subscribe(setOf("job.updated"))
        val watcher = JobWatcher(db, jobs.hub)
        runBlocking { watcher.pollOnce() }
        insert(internal, "jpdb-history-apply")
        insert(public, "jpdb-history-import")
        runBlocking { watcher.pollOnce() }
        db.txBlocking { it.execute("update app.job set progress = 0.5 where id in (?, ?)", internal, public) }
        runBlocking { watcher.pollOnce() }
        // A pg_notify hint naming the internal job changes nothing: events come from the (filtered) rows.
        PgListener("unused", jobs.hub) { }.handle("""{"type":"job.updated","data":{"id":"$internal"}}""")
        runBlocking { watcher.pollOnce() }

        val ids = generateSequence { sub.channel.tryReceive().getOrNull() }.map { json.readTree(it.json)["data"]["id"].asText() }.toList()
        assertTrue(ids.isNotEmpty() && ids.all { it == public.toString() }, "only the public job is announced: $ids")

        assertEquals(404, client.get("/api/v1/jobs/$internal").status.value)
        assertEquals(404, client.post("/api/v1/jobs/$internal/cancel").status.value)
        val listed = json.readTree(client.get("/api/v1/jobs").bodyAsText())["items"].map { it["id"].asText() }
        assertEquals(listOf(public.toString()), listed)
        assertEquals("running", db.txBlocking { it.fetchValue("select status from app.job where id = ?", internal) as String }, "cancel left it alone")
        db.close()
    }
}
