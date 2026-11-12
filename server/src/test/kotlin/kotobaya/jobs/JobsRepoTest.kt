package kotobaya.jobs

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotobaya.server.AppDeps
import kotobaya.server.Database
import kotobaya.server.TestPg
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.kotobayaModule
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobsRepoTest {
    private fun payload(s: String = "{}") = json.readTree(s)

    private fun Database.insertJob(kind: String, status: String = "queued", ageDays: Int = 0, mediaId: UUID? = null): UUID {
        val id = Uuid7.generate()
        txBlocking {
            val finished = if (status in setOf("succeeded", "failed", "cancelled")) "now() - make_interval(days => $ageDays)" else "null"
            it.execute(
                "insert into app.job (id, kind, status, payload, created_at, finished_at) " +
                    "values (?, ?, ?, ?::jsonb, now() - make_interval(days => $ageDays), $finished)",
                id, kind, status, if (mediaId != null) """{"media_id":"$mediaId"}""" else "{}",
            )
        }
        return id
    }

    @Test
    fun dedupeReturnsTheExistingQueuedOrRunningJob() {
        val db = TestPg.migratedDb()
        val a = db.txBlocking { JobRepo.enqueue(it, "media-parse", payload(), dedupeKey = "media-parse:m1") }
        val b = db.txBlocking { JobRepo.enqueue(it, "media-parse", payload(), dedupeKey = "media-parse:m1") }
        assertTrue(a.created)
        assertFalse(b.created)
        assertEquals(a.job.id, b.job.id)
        db.txBlocking { it.execute("update app.job set status = 'running' where id = ?", a.job.id) }
        assertEquals(a.job.id, db.txBlocking { JobRepo.enqueue(it, "media-parse", payload(), dedupeKey = "media-parse:m1") }.job.id)
        // once finished, the key is free again
        db.txBlocking { it.execute("update app.job set status = 'succeeded', finished_at = now() where id = ?", a.job.id) }
        val c = db.txBlocking { JobRepo.enqueue(it, "media-parse", payload(), dedupeKey = "media-parse:m1") }
        assertTrue(c.created)
        assertNotEquals(a.job.id, c.job.id)
        db.close()
    }

    @Test
    fun importIdRetryReturnsTheExistingJobInAnyStatusAndFallsBackToTheMostRecentMediaImport() {
        val db = TestPg.migratedDb()
        val importId = Uuid7.generate()
        val body = payload("""{"media_id":"$importId"}""")
        val first = db.txBlocking { JobRepo.enqueue(it, "media-import", body, dedupeKey = "media-import:$importId", id = importId) }
        assertTrue(first.created)
        for (status in listOf("queued", "running", "succeeded", "failed", "cancelled")) {
            db.txBlocking {
                it.execute(
                    "update app.job set status = ?, finished_at = case when ? in ('succeeded','failed','cancelled') then now() end where id = ?",
                    status, status, importId,
                )
            }
            val retry = db.txBlocking { JobRepo.enqueue(it, "media-import", body, dedupeKey = "media-import:$importId", id = importId) }
            assertFalse(retry.created, status)
            assertEquals(importId, retry.job.id)
            assertEquals(importId, db.txBlocking { JobRepo.resolveImportRetry(it, importId) }?.id)
        }
        // job row gone: the most recent media-import job for that media answers
        db.txBlocking { it.execute("delete from app.job where id = ?", importId) }
        assertNull(db.txBlocking { JobRepo.resolveImportRetry(it, importId) })
        val older = db.insertJob("media-import", "succeeded", ageDays = 5, mediaId = importId)
        val newer = db.insertJob("media-import", "succeeded", ageDays = 1, mediaId = importId)
        db.insertJob("media-import", "succeeded", ageDays = 0, mediaId = Uuid7.generate()) // another media
        assertEquals(newer, db.txBlocking { JobRepo.resolveImportRetry(it, importId) }?.id)
        assertNotEquals(older, newer)
        // an id used by a job of another kind is returned as is, so the caller can answer 409
        val tts = db.insertJob("tts")
        assertEquals("tts", db.txBlocking { JobRepo.resolveImportRetry(it, tts) }?.kind)
        db.close()
    }

    @Test
    fun retentionPrunesOldFinishedJobsButNeverImportRowsOrActiveJobs() {
        val db = TestPg.migratedDb()
        val importId = Uuid7.generate()
        val oldImport = db.insertJob("media-import", "succeeded", ageDays = 400, mediaId = importId)
        val oldJpdb = db.insertJob("jpdb-history-import", "failed", ageDays = 400)
        val oldTts = db.insertJob("tts", "succeeded", ageDays = 100)
        val oldFailedParse = db.insertJob("media-parse", "failed", ageDays = 100)
        val oldCancelled = db.insertJob("deck-build", "cancelled", ageDays = 100)
        val recentTts = db.insertJob("tts", "succeeded", ageDays = 10)
        val oldQueued = db.insertJob("tts", "queued", ageDays = 400)
        assertEquals(3, db.txBlocking { JobRepo.prune(it) })
        fun exists(id: UUID) = db.txBlocking { it.fetchValue("select count(*) from app.job where id = ?", id) as Long } == 1L
        assertTrue(exists(oldImport) && exists(oldJpdb) && exists(recentTts) && exists(oldQueued))
        assertFalse(exists(oldTts) || exists(oldFailedParse) || exists(oldCancelled))
        // the import_id retry the OpenAPI promises still answers after pruning
        assertEquals(oldImport, db.txBlocking { JobRepo.resolveImportRetry(it, importId) }?.id)
        assertEquals(oldImport, db.txBlocking { JobRepo.enqueue(it, "media-import", payload(), id = oldImport) }.job.id)
        db.close()
    }

    @Test
    fun cancelSemanticsForQueuedRunningCancelledAndFinishedJobs() = testApplication {
        val db = TestPg.migratedDb()
        val jobs = JobsModule(db, "unused")
        application { kotobayaModule(AppDeps(db, handlers = jobs.handlers)) }
        suspend fun cancel(id: UUID) = client.post("/api/v1/jobs/$id/cancel")

        val queued = db.insertJob("tts", "queued")
        val r1 = cancel(queued)
        assertEquals(200, r1.status.value)
        val j1 = json.readTree(r1.bodyAsText())
        assertEquals("cancelled", j1["status"].asText())
        assertTrue(!j1["finished_at"].isNull)

        val running = db.insertJob("tts", "running")
        db.txBlocking { it.execute("update app.job set locked_by = 'w1', started_at = now() where id = ?", running) }
        val j2 = json.readTree(cancel(running).bodyAsText())
        assertEquals("cancelled", j2["status"].asText()) // the status is the flag the worker polls at its heartbeat

        val again = cancel(queued)
        assertEquals(200, again.status.value)
        assertEquals(j1["finished_at"], json.readTree(again.bodyAsText())["finished_at"]) // unchanged

        for (status in listOf("succeeded", "failed")) {
            val r = cancel(db.insertJob("tts", status))
            assertEquals(409, r.status.value)
            assertEquals("job_not_cancellable", json.readTree(r.bodyAsText())["code"].asText())
        }
        assertEquals(404, cancel(UUID.randomUUID()).status.value)
        db.close()
    }

    @Test
    fun listAndGetPageNewestFirstAndHideServerInternalKinds() = testApplication {
        val db = TestPg.migratedDb()
        val jobs = JobsModule(db, "unused")
        application { kotobayaModule(AppDeps(db, handlers = jobs.handlers)) }
        val ids = (1..3).map { db.insertJob("tts", ageDays = 3 - it) } // oldest first
        val internal = db.insertJob("backup", "queued")

        val p1 = json.readTree(client.get("/api/v1/jobs?limit=2").bodyAsText())
        assertEquals(listOf(ids[2], ids[1]).map { it.toString() }, p1["items"].map { it["id"].asText() })
        val p2 = json.readTree(client.get("/api/v1/jobs?limit=2&cursor=${p1["next_cursor"].asText()}").bodyAsText())
        assertEquals(listOf(ids[0].toString()), p2["items"].map { it["id"].asText() })
        assertTrue(p2["next_cursor"].isNull)
        assertEquals(404, client.get("/api/v1/jobs/$internal").status.value)
        assertEquals(200, client.get("/api/v1/jobs/${ids[0]}").status.value)
        assertEquals(422, client.get("/api/v1/jobs?cursor=garbage").status.value)
        assertEquals(422, client.get("/api/v1/jobs?status=bogus").status.value)
        assertEquals(3, json.readTree(client.get("/api/v1/jobs?status=queued,running").bodyAsText())["items"].size())
        db.close()
    }
}
