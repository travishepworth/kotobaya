package kotobaya.ankiconnect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.auth.AnkiConnectError
import kotobaya.blobs.BlobStore
import kotobaya.server.Database
import java.security.MessageDigest
import java.util.Base64

private const val MAX_MEDIA_BYTES = 100L * 1024 * 1024
private val AUDIO = mapOf(
    "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "m4a" to "audio/mp4",
    "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac", "webm" to "audio/webm",
)
private val IMAGE = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    "svg" to "image/svg+xml", "avif" to "image/avif", "bmp" to "image/bmp",
)
private val EXT = Regex("^[A-Za-z0-9]{1,10}$")
private val SHA_NAME = Regex("^([0-9a-f]{64})(\\.[A-Za-z0-9]{1,10})?$")
private val SOUND = Regex("\\[sound:([^\\]]+)]")
private val IMG = Regex("(<img\\b[^>]*?\\bsrc=)([\"']?)([^\"'\\s>]+)\\2", RegexOption.IGNORE_CASE)

/** Media a client stored with `storeMediaFile`, and the rewriting of references to it in note fields. */
internal class AnkiMedia(private val db: Database, private val store: BlobStore?) {
    /** Original filename -> hash, so a later `addNote` can refer to the name the client chose. Bounded, in memory. */
    private val names = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 5000
    }

    /** Stores [b64] as a blob; returns `<sha256>.<original extension>` (just the hash when there is none). */
    suspend fun storeBase64(filename: String, b64: String): String {
        val bytes = try {
            Base64.getMimeDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw AnkiConnectError("data is not valid base64")
        }
        if (bytes.size > MAX_MEDIA_BYTES) throw AnkiConnectError("the file is too large")
        val hash = store(filename, bytes)
        val ext = extension(filename)
        synchronized(names) { names[filename] = hash }
        return if (ext == null) hash else "$hash.$ext"
    }

    /** The hash of a stored blob; the object is durable before its row exists. */
    suspend fun store(filename: String, bytes: ByteArray): String {
        val blobs = store ?: throw AnkiConnectError("media storage is not configured")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ext = extension(filename)?.lowercase()
        val (kind, type) = when {
            ext != null && ext in AUDIO -> "audio" to AUDIO.getValue(ext)
            ext != null && ext in IMAGE -> "image" to IMAGE.getValue(ext)
            else -> "upload" to "application/octet-stream"
        }
        withContext(Dispatchers.IO) {
            if (blobs.size(hash) != bytes.size.toLong()) blobs.put(hash, bytes.inputStream(), type)
            db.txBlocking { dsl ->
                dsl.execute(
                    "insert into app.blob (hash, size_bytes, content_type, kind, origin) values (?, ?, ?, ?, 'upload') on conflict (hash) do nothing",
                    hash, bytes.size.toLong(), type, kind,
                )
            }
        }
        return hash
    }

    private fun extension(filename: String): String? =
        filename.substringAfterLast('.', "").takeIf { it.isNotEmpty() && EXT.matches(it) }

    private fun hashOf(name: String): String? =
        SHA_NAME.matchEntire(name)?.groupValues?.get(1) ?: synchronized(names) { names[name] }

    /** `[sound:<name>]` becomes `[sound:<sha256>]`, `<img src="<name>">` becomes `<img src="blob:<sha256>">`; unknown names stay. */
    fun rewrite(html: String): String {
        val sounds = SOUND.replace(html) { m -> hashOf(m.groupValues[1])?.let { "[sound:$it]" } ?: m.value }
        return IMG.replace(sounds) { m -> hashOf(m.groupValues[3])?.let { "${m.groupValues[1]}\"blob:$it\"" } ?: m.value }
    }
}
