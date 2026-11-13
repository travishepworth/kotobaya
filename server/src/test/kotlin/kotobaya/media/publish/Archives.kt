package kotobaya.media.publish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.luben.zstd.ZstdOutputStream
import kotobaya.server.json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** The C-11 examples (contracts/db/media/examples) and helpers to build archives from records. */
object Archives {
    val examples: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "contracts/db/media/examples") }.first { it.isDirectory }
    }

    fun bytes(name: String): ByteArray = File(examples, name).readBytes()

    /** The records of an example `.jsonl`, as mutable trees. */
    fun records(name: String): MutableList<ObjectNode> =
        File(examples, name).readLines().filter { it.isNotEmpty() }.map { json.readTree(it) as ObjectNode }.toMutableList()

    fun payload(name: String): ObjectNode = json.readTree(File(examples, name)) as ObjectNode

    /** JSON Lines text of [records] (LF after every line). */
    fun text(records: List<JsonNode>): ByteArray =
        records.joinToString("") { json.writeValueAsString(it) + "\n" }.toByteArray(Charsets.UTF_8)

    fun zstd(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZstdOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun archive(records: List<JsonNode>): ByteArray = zstd(text(records))

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Sets header counts from the records that follow (content archives). */
    fun recount(records: List<ObjectNode>): List<ObjectNode> {
        val head = records[0]
        val counts = head.withObjectProperty("counts")
        if (head["format"].asText() == SnapshotReader.CONTENT_FORMAT) {
            for (k in listOf("segment", "sentence", "occurrence", "grammar_span")) counts.put(k, records.count { it["record"].asText() == k })
        } else {
            counts.put("item", records.count { it["record"].asText() == "item" })
        }
        return records
    }
}
