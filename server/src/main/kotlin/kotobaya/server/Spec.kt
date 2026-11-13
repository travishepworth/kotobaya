package kotobaya.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.ktor.http.HttpMethod

/** One alternative of an operation's `security`: scheme name to scopes. `{}` (empty) means "no header needed". */
typealias SecurityAlternative = Map<String, List<String>>

data class Operation(
    val operationId: String,
    val method: HttpMethod,
    /** Path relative to the spec's server base (`/api/v1`), with `{param}` segments (Ktor uses the same syntax). */
    val path: String,
    /** Empty list = public (`security: []`). */
    val security: List<SecurityAlternative>,
    /** The custom `x-auth` extension (only `body-key` exists), or null. */
    val xAuth: String?,
    val xBodyKeyScope: String?,
    /** Declared response keys: status codes as text, plus `default`. */
    val responseCodes: Set<String>,
)

/** The frozen `contracts/api/openapi.yaml`, loaded from the classpath copy made at build time. */
class ApiSpec(val root: JsonNode) {
    val basePath: String = root.path("servers").path(0).path("url").asText()

    val operations: List<Operation> = buildList {
        for ((path, item) in root.path("paths").fields()) {
            for (method in listOf("get", "post", "put", "patch", "delete")) {
                val op = item.get(method) ?: continue
                add(
                    Operation(
                        operationId = op.path("operationId").asText(),
                        method = HttpMethod.parse(method.uppercase()),
                        path = path,
                        security = op.path("security").map { alt ->
                            alt.fields().asSequence().associate { (k, v) -> k to v.map { it.asText() } }
                        },
                        xAuth = op.get("x-auth")?.asText(),
                        xBodyKeyScope = op.get("x-body-key-scope")?.asText(),
                        responseCodes = op.path("responses").fieldNames().asSequence().toSet(),
                    ),
                )
            }
        }
    }

    /** Resolve a `$ref` or inline schema node of a response (`application/json`). */
    fun responseSchema(operationId: String, status: String): JsonNode? {
        for ((_, item) in root.path("paths").fields()) {
            for ((_, op) in item.fields()) {
                if (op.path("operationId").asText() != operationId) continue
                val schema = op.path("responses").path(status).path("content").path("application/json").path("schema")
                return if (schema.isMissingNode) null else resolve(schema)
            }
        }
        return null
    }

    fun resolve(node: JsonNode): JsonNode {
        val ref = node.get("\$ref")?.asText() ?: return node
        var cur: JsonNode = root
        for (part in ref.removePrefix("#/").split('/')) cur = cur.path(part)
        return resolve(cur)
    }

    companion object {
        private val cached: ApiSpec by lazy { parse() }

        /** Parsed once per JVM; the spec is immutable. */
        fun load(): ApiSpec = cached

        private fun parse(): ApiSpec {
            val stream = ApiSpec::class.java.getResourceAsStream("/contracts/openapi.yaml")
                ?: error("contracts/openapi.yaml missing from the classpath (processResources copies it)")
            return stream.use { ApiSpec(ObjectMapper(YAMLFactory()).readTree(it)) }
        }
    }
}
