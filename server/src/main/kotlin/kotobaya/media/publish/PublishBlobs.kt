package kotobaya.media.publish

import kotobaya.blobs.BlobStore
import kotobaya.server.Database
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

/** The blob fetch shared by the publish runners (MEDIA_PIPELINE.md 3.3 step 3, VOCAB_LISTS.md 5.2 step 3). */
internal object PublishBlobs {
    /**
     * Downloads [ref] to a temp file (added to [files]; the caller deletes them), checking the `app.blob` row, the
     * object, the length and the SHA-256. Throws [PublishFailure].
     */
    fun fetch(db: Database, store: BlobStore, ref: BlobRef, files: MutableList<Path>, prefix: String): Path {
        val hash = ref.blobHash
        val row = db.txBlocking { it.fetchExists(it.selectOne().from("app.blob").where("hash = ?", hash)) }
        if (!row) throw PublishFailure(PublishFailure.BLOB_MISSING, "no app.blob row for $hash")
        store.size(hash) ?: throw PublishFailure(PublishFailure.BLOB_MISSING, "no object $hash in the object store")
        val file = Files.createTempFile(prefix, ".zst")
        files.add(file)
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        DigestInputStream(store.get(hash), digest).use { input ->
            Files.newOutputStream(file).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    length += n
                    if (length > ref.sizeBytes) {
                        throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "blob $hash is longer than size_bytes ${ref.sizeBytes}")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        if (length != ref.sizeBytes) throw PublishFailure(PublishFailure.INVALID_PAYLOAD, "blob $hash is $length bytes, size_bytes says ${ref.sizeBytes}")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != hash) throw PublishFailure(PublishFailure.INVALID_SNAPSHOT, "object $hash has SHA-256 $actual")
        return file
    }
}
