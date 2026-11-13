package kotobaya.export

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import kotobaya.blobs.BlobStore
import kotobaya.notes.Errors
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.server.SERVER_VERSION
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `exportAll` and `exportAnki` (T608). Read-only: no ops are emitted. Wiring (Main.kt):
 * `handlers += ExportModule(db, blobStore).handlers`.
 *
 * [blobStore] may be null (no object store configured): the .apkg then carries no media files.
 */
class ExportModule(
    private val db: Database,
    private val blobStore: BlobStore?,
    private val clock: () -> Instant = Instant::now,
    private val serverVersion: String = SERVER_VERSION,
) {
    val handlers: Map<String, OperationHandler> = mapOf(
        "exportAll" to { exportAll(this) },
        "exportAnki" to { exportAnki(this) },
    )

    private suspend fun exportAll(call: ApplicationCall) {
        val raw = call.request.queryParameters.getAll("include").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val bad = raw.filter { it != "media_content" }
        if (bad.isNotEmpty()) {
            Errors().apply { add("/include", "invalid_value", "Unknown section(s): ${bad.joinToString()}. Allowed: media_content.") }.throwIfAny()
        }
        val dump = JsonDump(db, serverVersion)
        val snapshot = dump.open(mediaContent = raw.isNotEmpty())
        try {
            call.response.header("Content-Disposition", "attachment; filename=\"kotobaya-export-${utcDate()}.json\"")
            call.respondOutputStream(ContentType.Application.Json) { snapshot.writeTo(this) }
        } finally {
            snapshot.close()
        }
    }

    private suspend fun exportAnki(call: ApplicationCall) {
        val query = call.request.queryParameters["query"]
        val pkg = AnkiPackage.build(db, blobStore, query, clock())
        try {
            call.response.header("Content-Disposition", "attachment; filename=\"kotobaya-${utcDate()}.apkg\"")
            call.respondOutputStream(ContentType.Application.OctetStream) { pkg.writeTo(this) }
        } finally {
            pkg.close()
        }
    }

    private fun utcDate(): String = DateTimeFormatter.ISO_LOCAL_DATE.format(clock().atZone(ZoneOffset.UTC))
}
