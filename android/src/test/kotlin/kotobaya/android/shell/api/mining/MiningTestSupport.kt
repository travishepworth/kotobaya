package kotobaya.android.shell.api.mining

import java.io.File
import java.sql.DriverManager
import kotobaya.android.dict.DictGapRemote
import kotobaya.android.dict.RemoteAnswer
import kotobaya.android.net.ApiError
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityDictionary
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityTemp
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** Helpers of the SHELL-L5 tests: [ParityDevice]s over every local route, a fake server and a fake gap-fill remote. */
internal object MiningTestSupport {
    const val T0 = 1_795_168_800_000L
    const val X = "01931f3e-0000-7000-8000-0000000000d4"
    const val VOCAB = "00000000-0000-7000-8000-000000000101"
    const val KANJI = "00000000-0000-7000-8000-000000000102"

    /** An entry of the fixture package (学校 is 1206730 in the server tests; 1000220 is in every scenario). */
    const val IN_PACKAGE = 1000220L

    /** A seq outside the package: "a word outside the top 60k" the server knows. */
    const val OUTSIDE = 5_000_001L

    fun device(
        remote: DictGapRemote? = null,
        withDictionary: Boolean = true,
        id: String = X,
    ): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(id, T0, LocalRoutes.all, remote = remote, withDictionary = withDictionary)
    }

    fun ParityDevice.call(method: String, path: String, body: String? = null, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ParityDevice.get(path: String, query: String = ""): ShellResponse = call("GET", path, query = query)

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun ShellResponse.code(): String? = (json().members["code"] as? JsonString)?.value

    /** `errors[0]` as `pointer code`. */
    fun ShellResponse.fieldError(): String {
        val first = ParityJson.obj((json().members["errors"] as JsonArray).items.first())
        return "${ParityJson.str(first.members["pointer"])} ${ParityJson.str(first.members["code"])}"
    }

    fun ShellResponse.seqs(): List<Long> = ParityJson.arr(json().members["items"]).map { ParityJson.long(ParityJson.obj(it).members["seq"]) }

    /** The package is older than the server's release: not authoritative (L-7), like a `top60k` package. */
    fun ParityDevice.serverReleaseAhead() = gapCache.recordServerRelease(dictionary.releaseId + 1)

    /** A `DictEntry` body the server would send for [seq]: the fixture's [like] entry renumbered. */
    fun entryBody(device: ParityDevice, seq: Long, like: Long = IN_PACKAGE, headword: String? = null): String {
        val r = device.get("/api/v1/dict/entries/$like")
        check(r.status == 200) { "fixture entry $like: ${r.status}" }
        val m = LinkedHashMap(r.json().members)
        m["seq"] = JsonNumber(seq.toDouble())
        headword?.let { m["headword"] = JsonString(it) }
        return ParityJson.encode(JsonObject(m))
    }

    /** L-7 step 3: the server's `getDictEntry` / `getKanji`. [online] false = every call fails as unreachable. */
    class FakeRemote(
        val entries: MutableMap<Long, String> = HashMap(),
        var online: Boolean = true,
    ) : DictGapRemote {
        val asked = ArrayList<String>()

        override suspend fun serverRelease(): Long? = null

        override suspend fun entry(seq: Long): RemoteAnswer {
            asked += "entry $seq"
            if (!online) throw ApiError.Offline
            return entries[seq]?.let { RemoteAnswer.Found(it) } ?: RemoteAnswer.NotFound
        }

        override suspend fun kanji(literal: String): RemoteAnswer {
            asked += "kanji $literal"
            if (!online) throw ApiError.Offline
            return RemoteAnswer.NotFound
        }
    }

    /** A server behind the shell's proxy answering every forwarded request with [answer]. */
    class FakeServer(private val answer: (String) -> Pair<Int, JsonValue>) : Upstream {
        val seen = ArrayList<String>()

        override suspend fun forward(
            request: kotobaya.android.shell.api.ShellRequest,
            operation: kotobaya.android.shell.api.ApiOperation,
            requestId: String,
        ): ShellResponse {
            seen += operation.operationId
            val (status, body) = answer(operation.operationId)
            return ShellResponse(status, mapOf(ShellHeaders.CONTENT_TYPE to ShellHeaders.JSON, "x-request-id" to requestId), ParityJson.encode(body))
        }
    }

    fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

    /**
     * The fixture package as the server's dictionary tests see it (`server/.../DictFixture`): `freq_rank` = row number
     * over `seq` among common entries, and 1584660 retired (its forms and glosses dropped). Installed over [device]'s
     * package before anything opened it.
     */
    fun installServerTestDictionary(device: ParityDevice) {
        val file = ParityTemp.next("server-test-dict")
        ParityDictionary.fixture.packageFile.copyTo(file, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "UPDATE dict_entry SET freq_rank = (SELECT n FROM (SELECT seq, row_number() OVER (ORDER BY seq) AS n FROM dict_entry WHERE is_common = 1) r " +
                        "WHERE r.seq = dict_entry.seq)",
                )
                for (seq in RETIRED) {
                    s.execute("UPDATE dict_entry SET retired = 1 WHERE seq = $seq")
                    s.execute("DELETE FROM dict_entry_form WHERE seq = $seq")
                    s.execute("DELETE FROM dict_entry_gloss WHERE seq = $seq")
                }
            }
        }
        file.copyTo(device.dictStore.installed, overwrite = true)
        file.delete()
    }

    val RETIRED = listOf(1584660L)

    val queriesFile: File get() = File(kotobaya.android.shell.parity.ParityScenario.repoRoot, "server/src/test/resources/dict/queries.json")
}
