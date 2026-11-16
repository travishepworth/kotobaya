package kotobaya.android.shell.api

import kotlin.reflect.KClass
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/**
 * A handler for one operation. [operationId] must be a `local` / `local_or_proxy` operation of
 * `ops.json` or a `shell.yaml` operation, and [method] + [pathTemplate] must be its own (checked by
 * [LocalApi] at construction). A handler may return [Fallthrough] to have the request forwarded.
 */
class LocalRoute(
    val operationId: String,
    val method: String,
    val pathTemplate: String,
    val handler: suspend (LocalCall) -> ShellResponse,
)

/**
 * What a handler's dependencies are looked up in at call time (the group `Routes.kt` files are
 * top-level values, so they cannot take constructor arguments). Production: Koin (`ShellModule`).
 */
interface ShellServices {
    fun <T : Any> get(type: KClass<T>): T

    companion object {
        /** No services (kernel tests). */
        val None: ShellServices = object : ShellServices {
            override fun <T : Any> get(type: KClass<T>): T = error("no service ${type.simpleName}")
        }
    }
}

/** `call.services.get<Foo>()`. */
inline fun <reified T : Any> ShellServices.get(): T = get(T::class)

/** One request being handled: the matched operation, its parameters and the body, with server-identical validation. */
class LocalCall(
    val request: ShellRequest,
    val operation: ApiOperation,
    /** Decoded path parameters by template name. */
    val pathParams: Map<String, String>,
    /** The `x-request-id` of this request (SHELL.md 3.3). */
    val requestId: String,
    val services: ShellServices,
) {
    /** Path parameter [name]; the template guarantees it is present. */
    fun path(name: String): String = pathParams[name] ?: error("no path parameter $name in ${operation.pathTemplate}")

    /**
     * The query parameters, after rejecting any name not in [allowed] with the server's
     * `422 validation_failed` (`/<name>`, `unknown_field`; the first unknown name, openapi section 1).
     */
    fun query(vararg allowed: String): QueryParams = query(allowed.toSet())

    fun query(allowed: Set<String>): QueryParams {
        request.query.keys.firstOrNull { it !in allowed }?.let {
            throw ProblemException(Problems.validationFailed("/$it", "unknown_field", "Unknown query parameter $it."))
        }
        return QueryParams(request.query)
    }

    /**
     * The body as a JSON object (server `jsonObject()` + `rejectUnknown`): not valid JSON → `400
     * invalid_request`; missing or not an object → `422` at `""` (`invalid_type`); a member not in
     * [allowed] → `422` at `/<name>` (`unknown_field`; the first one in document order).
     */
    fun body(allowed: Set<String>): JsonObject {
        val obj = bodyObject()
        obj.members.keys.firstOrNull { it !in allowed }?.let {
            throw ProblemException(Problems.validationFailed("/$it", "unknown_field", "Unknown property $it."))
        }
        return obj
    }

    /** The body as a JSON object without the unknown-property check (handlers that report every error themselves). */
    fun bodyObject(): JsonObject = when (val v = bodyValue()) {
        is JsonObject -> v
        else -> throw ProblemException(Problems.validationFailed("", "invalid_type", "The body must be a JSON object."))
    }

    /** The body as any JSON value; `null` when there is none. Not valid JSON → `400 invalid_request`. */
    fun bodyValue(): JsonValue? {
        val text = request.body?.takeIf { request.method != "GET" } ?: return null
        val decoded = DefaultCanonicalJson.decode(text)
        return decoded.value ?: throw ProblemException(Problems.invalidRequest("The body is not valid JSON."))
    }
}

/** Typed access to decoded query parameters. */
class QueryParams(private val values: Map<String, List<String>>) {
    val names: Set<String> get() = values.keys

    operator fun contains(name: String): Boolean = name in values

    /** The first value of [name], or null. */
    fun first(name: String): String? = values[name]?.firstOrNull()

    /** Every value of [name] in request order. */
    fun all(name: String): List<String> = values[name].orEmpty()

    /**
     * An integer parameter in [range]; absent → [default]. Anything else adds `out_of_range` at
     * `/<name>` to [errors] (the server's `limit` handling) and returns [default].
     */
    fun int(name: String, range: IntRange, default: Int, errors: FieldErrors): Int {
        val v = first(name) ?: return default
        val n = v.toIntOrNull()?.takeIf { it in range }
        if (n == null) {
            errors.add("/$name", "out_of_range", "Must be ${range.first} to ${range.last}.")
            return default
        }
        return n
    }

    /** `true` / `false`; absent → [default]; anything else adds `invalid_value` at `/<name>`. */
    fun bool(name: String, default: Boolean, errors: FieldErrors): Boolean = when (val v = first(name)) {
        null -> default
        "true" -> true
        "false" -> false
        else -> {
            errors.add("/$name", "invalid_value", "Must be true or false.")
            default
        }
    }
}
