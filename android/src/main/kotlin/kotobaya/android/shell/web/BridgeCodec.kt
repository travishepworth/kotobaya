package kotobaya.android.shell.web

import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/** Who sent a bridge message (the directional schemas of `bridge.schema.json`). */
enum class BridgeDirection { WEB_TO_NATIVE, NATIVE_TO_WEB }

/** One bridge message of protocol version 1 (SHELL.md 2.2). Unknown properties are dropped on decode. */
sealed interface BridgeMessage {
    val t: String

    // web -> native
    data class Req(
        val id: String,
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String?,
    ) : BridgeMessage {
        override val t: String get() = "req"
    }

    data class Ready(val webVersion: String, val minLocalApi: Long) : BridgeMessage {
        override val t: String get() = "ready"
    }

    data class BackResult(val id: String, val handled: Boolean) : BridgeMessage {
        override val t: String get() = "back.result"
    }

    data class BackState(val canHandle: Boolean) : BridgeMessage {
        override val t: String get() = "back.state"
    }

    data class Haptic(val kind: String) : BridgeMessage {
        override val t: String get() = "haptic"
    }

    data class Theme(val dark: Boolean, val surface: String) : BridgeMessage {
        override val t: String get() = "theme"
    }

    // native -> web
    data class Res(val id: String, val status: Int, val headers: Map<String, String>, val body: String?) : BridgeMessage {
        override val t: String get() = "res"
    }

    /** `event`: [type], [at] (RFC 3339 UTC ms), [data] (an object). */
    data class Event(val type: String, val at: String, val data: JsonObject) : BridgeMessage {
        override val t: String get() = "event"
    }

    data class Navigate(val path: String) : BridgeMessage {
        override val t: String get() = "navigate"
    }

    data class Back(val id: String) : BridgeMessage {
        override val t: String get() = "back"
    }
}

/** Outcome of decoding one message text. */
sealed interface Decoded {
    data class Valid(val message: BridgeMessage) : Decoded

    /** A syntactically valid v1 message whose `t` the receiver does not know: ignored (SHELL.md 2.3). */
    data class Unknown(val t: String) : Decoded

    /** Not JSON, not an object, `v` ≠ 1, a known `t` with bad fields, or a type of the other direction: dropped. */
    data class Invalid(val reason: String) : Decoded
}

/**
 * Decoder and encoder of the bridge (`contracts/api/shell/bridge.schema.json`). Pure Kotlin; no Android types.
 * Decoding validates exactly the constraints of the schema for the given direction.
 */
object BridgeCodec {
    const val MAX_BODY_CHARS: Int = 4_194_304

    private val WEB_TYPES = setOf("req", "ready", "back.result", "back.state", "haptic", "theme")
    private val NATIVE_TYPES = setOf("res", "event", "navigate", "back")
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    private val HAPTICS = setOf("tick", "confirm", "reject")

    private val TYPE_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$")
    private val ID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val PATH_RE = Regex("^/(api|shell)/v1/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*$")
    private val DOT_SEGMENT_RE = Regex("(^|/)\\.{1,2}(/|$)")
    private val ENCODED_BAD_RE = Regex("%(2[eEfF]|5[cC]|00)")
    private val QUERY_RE = Regex("^([A-Za-z0-9._~!$&'()*+,;=:@%/-][A-Za-z0-9._~!$&'()*+,;=:@%/?-]*)?$")
    private val HEADER_NAME_RE = Regex("^[a-z0-9!#$%&'*+.^_`|~-]+$")
    private val HEADER_VALUE_BAD_RE = Regex("[\\r\\n\\u0000]")
    private val WEB_VERSION_RE = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]*$")
    private val AT_RE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val SURFACE_RE = Regex("^#[0-9a-fA-F]{6}$")

    /** Navigation path rule of SHELL.md 6.1 (also the `navigate.path` schema). */
    fun isValidNavPath(path: String): Boolean =
        path.length <= 2048 && NavPath.PATTERN.matches(path) && "//" !in path

    fun decode(text: String, from: BridgeDirection): Decoded {
        val root = DefaultCanonicalJson.decode(text).value as? JsonObject ?: return Decoded.Invalid("not a JSON object")
        val m = root.members
        val v = m["v"] as? JsonNumber ?: return Decoded.Invalid("no v")
        if (v.value != 1.0) return Decoded.Invalid("v is not 1")
        val t = (m["t"] as? JsonString)?.value ?: return Decoded.Invalid("no t")
        if (t.length > 40 || !TYPE_RE.matches(t)) return Decoded.Invalid("bad t")
        val mine = if (from == BridgeDirection.WEB_TO_NATIVE) WEB_TYPES else NATIVE_TYPES
        val theirs = if (from == BridgeDirection.WEB_TO_NATIVE) NATIVE_TYPES else WEB_TYPES
        if (t in theirs) return Decoded.Invalid("$t sent in the wrong direction")
        if (t !in mine) return Decoded.Unknown(t)
        return try {
            Decoded.Valid(fields(t, Fields(m)))
        } catch (e: BadField) {
            Decoded.Invalid("$t: ${e.message}")
        }
    }

    private class BadField(msg: String) : Exception(msg)

    private class Fields(val m: Map<String, JsonValue>) {
        fun bad(name: String): Nothing = throw BadField(name)

        fun string(name: String): String = (m[name] as? JsonString)?.value ?: bad(name)

        fun stringOrNull(name: String): String? = when (val x = m[name]) {
            is JsonString -> x.value
            JsonNull -> null
            else -> bad(name)
        }

        fun bool(name: String): Boolean = (m[name] as? JsonBool)?.value ?: bad(name)

        fun integer(name: String): Long {
            val n = (m[name] as? JsonNumber)?.value ?: bad(name)
            if (!n.isFinite() || n != Math.floor(n) || Math.abs(n) > 9.007199254740991E15) bad(name)
            return n.toLong()
        }

        fun obj(name: String): JsonObject = m[name] as? JsonObject ?: bad(name)

        fun id(name: String = "id"): String = string(name).also { if (!ID_RE.matches(it)) bad(name) }

        fun headers(): Map<String, String> {
            val h = obj("headers").members
            if (h.size > 64) bad("headers")
            val out = LinkedHashMap<String, String>()
            for ((k, v) in h) {
                if (k.length > 64 || !HEADER_NAME_RE.matches(k)) bad("headers")
                val s = (v as? JsonString)?.value ?: bad("headers")
                if (s.length > 8192 || HEADER_VALUE_BAD_RE.containsMatchIn(s)) bad("headers")
                out[k] = s
            }
            return out
        }
    }

    private fun fields(t: String, f: Fields): BridgeMessage = when (t) {
        "req" -> {
            val id = f.id()
            val method = f.string("method").also { if (it !in METHODS) f.bad("method") }
            val path = f.string("path").also {
                if (it.length > 2048 || !PATH_RE.matches(it) || "//" in it ||
                    DOT_SEGMENT_RE.containsMatchIn(it) || ENCODED_BAD_RE.containsMatchIn(it)
                ) {
                    f.bad("path")
                }
            }
            val query = f.string("query").also { if (it.length > 8192 || !QUERY_RE.matches(it)) f.bad("query") }
            val headers = f.headers()
            if ("body" !in f.m) f.bad("body")
            val body = f.stringOrNull("body")?.also { if (it.length > MAX_BODY_CHARS) f.bad("body") }
            BridgeMessage.Req(id, method, path, query, headers, body)
        }
        "ready" -> {
            val wv = f.string("web_version").also { if (it.isEmpty() || it.length > 64 || !WEB_VERSION_RE.matches(it)) f.bad("web_version") }
            val min = f.integer("min_local_api").also { if (it < 1) f.bad("min_local_api") }
            BridgeMessage.Ready(wv, min)
        }
        "back.result" -> BridgeMessage.BackResult(f.id(), f.bool("handled"))
        "back.state" -> BridgeMessage.BackState(f.bool("can_handle"))
        "haptic" -> BridgeMessage.Haptic(f.string("kind").also { if (it !in HAPTICS) f.bad("kind") })
        "theme" -> {
            val dark = f.bool("dark")
            val surface = f.string("surface").also { if (!SURFACE_RE.matches(it)) f.bad("surface") }
            BridgeMessage.Theme(dark, surface)
        }
        "res" -> {
            val id = f.id()
            val status = f.integer("status").also { if (it !in 200..599) f.bad("status") }
            val headers = f.headers()
            if ("body" !in f.m) f.bad("body")
            BridgeMessage.Res(id, status.toInt(), headers, f.stringOrNull("body"))
        }
        "event" -> {
            val e = Fields(f.obj("event").members)
            val type = e.string("type").also { if (it.length > 40 || !TYPE_RE.matches(it)) e.bad("event.type") }
            val at = e.string("at").also { if (!AT_RE.matches(it)) e.bad("event.at") }
            BridgeMessage.Event(type, at, e.obj("data"))
        }
        "navigate" -> BridgeMessage.Navigate(f.string("path").also { if (!isValidNavPath(it)) f.bad("path") })
        "back" -> BridgeMessage.Back(f.id())
        else -> throw BadField("unhandled type $t")
    }

    /** One JSON text for [message]. Header names are lower-cased. */
    fun encode(message: BridgeMessage): String {
        val o = LinkedHashMap<String, JsonValue>()
        o["v"] = JsonNumber(1.0)
        o["t"] = JsonString(message.t)
        fun headers(h: Map<String, String>) = JsonObject(h.entries.associate { (k, v) -> k.lowercase() to JsonString(v) })
        fun nullable(s: String?): JsonValue = s?.let(::JsonString) ?: JsonNull
        when (message) {
            is BridgeMessage.Req -> {
                o["id"] = JsonString(message.id)
                o["method"] = JsonString(message.method)
                o["path"] = JsonString(message.path)
                o["query"] = JsonString(message.query)
                o["headers"] = headers(message.headers)
                o["body"] = nullable(message.body)
            }
            is BridgeMessage.Ready -> {
                o["web_version"] = JsonString(message.webVersion)
                o["min_local_api"] = JsonNumber(message.minLocalApi.toDouble())
            }
            is BridgeMessage.BackResult -> {
                o["id"] = JsonString(message.id)
                o["handled"] = JsonBool(message.handled)
            }
            is BridgeMessage.BackState -> o["can_handle"] = JsonBool(message.canHandle)
            is BridgeMessage.Haptic -> o["kind"] = JsonString(message.kind)
            is BridgeMessage.Theme -> {
                o["dark"] = JsonBool(message.dark)
                o["surface"] = JsonString(message.surface)
            }
            is BridgeMessage.Res -> {
                o["id"] = JsonString(message.id)
                o["status"] = JsonNumber(message.status.toDouble())
                o["headers"] = headers(message.headers)
                o["body"] = nullable(message.body)
            }
            is BridgeMessage.Event -> o["event"] = JsonObject(
                linkedMapOf("type" to JsonString(message.type), "at" to JsonString(message.at), "data" to message.data),
            )
            is BridgeMessage.Navigate -> o["path"] = JsonString(message.path)
            is BridgeMessage.Back -> o["id"] = JsonString(message.id)
        }
        return DefaultCanonicalJson.encode(JsonObject(o))
    }
}
