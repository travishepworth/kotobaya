package kotobaya.server

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options", "trace")

/** Counts operations straight from the frozen contract file, independent of [ApiSpec]. */
private fun contractOperationCount(): Int {
    val root = com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        .readTree(java.io.File("../contracts/api/openapi.yaml"))
    return root.path("paths").elements().asSequence().sumOf { item -> item.fieldNames().asSequence().count { it in HTTP_METHODS } }
}

/** OpenAPI conformance: the routing table equals the spec, and implemented operations answer as declared. */
class ConformanceTest {
    private fun leaves(route: Route, out: MutableSet<String> = linkedSetOf()): Set<String> {
        val node = route as RoutingNode
        val sel = node.selector
        if (sel is HttpMethodRouteSelector) {
            out += "${sel.method.value} ${route.toString().substringBeforeLast("/(method:")}"
        }
        node.children.forEach { leaves(it, out) }
        return out
    }

    @Test
    fun everySpecOperationIsRoutedAndNothingElse() {
        val db = TestPg.deadDb()
        val deps = AppDeps(db)
        var routes: Set<String> = emptySet()
        testApplication {
            application {
                kotobayaModule(deps)
                routes = leaves(routing { })
            }
            startApplication()
        }
        val expected = deps.spec.operations.map { "${it.method.value} ${deps.spec.basePath}${it.path}" }.toSet() +
            setOf("GET /healthz", "GET /readyz", "GET /metrics")
        assertEquals(contractOperationCount(), deps.spec.operations.size, "operations in contracts/api/openapi.yaml")
        assertEquals(expected.sorted(), routes.sorted())
        db.close()
    }

    @Test
    fun everyOperationAnswersWithADeclaredStatusAndUnimplementedOnesAre501Problems() {
        val db = TestPg.migratedDb()
        StartupSequence(db).runOnce()
        val deps = AppDeps(db)
        val implemented = setOf("getHealth", "getMeta")
        testApplication {
            application { kotobayaModule(deps) }
            for (op in deps.spec.operations) {
                val path = deps.spec.basePath + op.path.replace(Regex("\\{[^}]+}"), "x")
                val resp = client.request(path) { method = op.method }
                val status = resp.status.value.toString()
                val body = resp.bodyAsText()
                val label = "${op.method.value} $path"
                if (op.operationId in implemented) {
                    assertEquals("200", status, label)
                    assertTrue("200" in op.responseCodes, label)
                    assertTrue(resp.contentType().toString().startsWith("application/json"), label)
                    assertMatchesSchema(deps.spec, deps.spec.responseSchema(op.operationId, "200")!!, json.readTree(body), label)
                } else {
                    assertEquals("501", status, label)
                    assertTrue("default" in op.responseCodes, label)
                    assertEquals("application/problem+json", resp.contentType()?.withoutParameters().toString(), label)
                    assertMatchesSchema(deps.spec, deps.spec.resolve(deps.spec.root.path("components").path("schemas").path("Problem")), json.readTree(body), label)
                }
                assertNotNull(resp.headers["X-Request-Id"], label)
            }
        }
        db.close()
    }

    /** Required properties present, scalar types and enum/const respected (enough for the implemented operations). */
    private fun assertMatchesSchema(spec: ApiSpec, schema: JsonNode, value: JsonNode, at: String) {
        for (req in schema.path("required")) assertTrue(value.has(req.asText()), "$at: missing ${req.asText()}")
        for ((name, raw) in schema.path("properties").fields()) {
            val v = value.get(name) ?: continue
            val s = spec.resolve(raw)
            if (s.has("const")) assertEquals(s.get("const").asText(), v.asText(), "$at: $name const")
            if (s.has("enum")) assertTrue(s.get("enum").any { it.asText() == v.asText() }, "$at: $name enum")
            when (s.path("type").asText()) {
                "string" -> assertTrue(v.isTextual, "$at: $name string")
                "integer" -> assertTrue(v.isIntegralNumber, "$at: $name integer")
                "object" -> if (s.has("properties")) assertMatchesSchema(spec, s, v, "$at.$name")
            }
        }
    }

    @Test
    fun methodAndPathsOutsideTheSpecAreNotFoundProblems() = testApplication {
        val db = TestPg.deadDb()
        application { kotobayaModule(AppDeps(db)) }
        assertEquals(404, client.request("/api/v1/nope") { method = HttpMethod.Get }.status.value)
        val wrong = client.request("/api/v1/health") { method = HttpMethod.Delete }
        assertEquals(405, wrong.status.value)
        assertEquals("method_not_allowed", json.readTree(wrong.bodyAsText())["code"].asText())
    }
}
