package kotobaya.sync

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The embedded JSON Schema 2020-12 subset validator (FOLLOWUPS C-1 -> T303/T107). */
class SyncSchemasTest {
    private val j = DefaultCanonicalJson

    private fun v(text: String): JsonValue = j.decode(text).value!!

    private fun err(schema: String, instance: String): SchemaError? =
        SyncSchemas.validateWith(v(schema), "op.schema.json", v(instance))

    @Test
    fun everyOpTypeHasAnEmbeddedSchemaAndNoOtherFiles() {
        val expected = OpType.entries.map { "ops/${it.wire}.schema.json" }.toSet() +
            setOf("common.schema.json", "op.schema.json", "batch.schema.json")
        assertEquals(expected, SyncSchemas.files)
    }

    /** If a schema starts using a keyword the validator does not know, this fails (instead of silently passing ops). */
    @Test
    fun schemasUseOnlySupportedKeywords() {
        val used = HashSet<String>()
        // walks schema positions only: names under properties/$defs/dependentRequired are not keywords
        fun schema(node: JsonValue) {
            if (node !is JsonObject) return
            for ((k, x) in node.members) {
                used.add(k)
                when (k) {
                    "properties", "\$defs" -> (x as JsonObject).members.values.forEach(::schema)
                    "allOf", "anyOf", "oneOf", "prefixItems" -> (x as JsonArray).items.forEach(::schema)
                    "items", "additionalProperties", "propertyNames", "if", "then", "else" -> schema(x)
                    else -> Unit
                }
            }
        }
        for (f in SyncSchemas.files) schema(SyncSchemas.schema(f)!!)
        val unknown = used - SyncSchemas.SUPPORTED_KEYWORDS - SyncSchemas.ANNOTATIONS
        assertEquals(emptySet(), unknown)
        // and the keywords the ticket names are really used (so their tests below are not vacuous)
        assertTrue(used.containsAll(listOf("prefixItems", "items", "dependentRequired", "propertyNames", "if", "oneOf")))
    }

    @Test
    fun prefixItemsAndItemsFalse() {
        val s = """{"type":"array","prefixItems":[{"type":"integer"},{"type":"string"}],"items":false,"minItems":2}"""
        assertNull(err(s, """[1,"a"]"""))
        assertEquals("/1", err(s, """[1,2]""")!!.pointer)
        assertEquals("", err(s, """[1,"a",3]""")!!.pointer)
        assertEquals("", err(s, """[1]""")!!.pointer)
        val bounded = """{"prefixItems":[{"minimum":0.001,"maximum":100}]}"""
        assertEquals("/0", err(bounded, "[0]")!!.pointer)
        assertNull(err(bounded, "[0.001]"))
        assertNull(err(bounded, "[1,\"anything after the prefix\"]"))
    }

    @Test
    fun integerTypeMeansIntegralBelow2pow53() {
        val s = """{"type":"integer"}"""
        assertNull(err(s, "3"))
        assertNull(err(s, "3.0"))
        assertNull(err(s, "-9007199254740991"))
        assertNotNull(err(s, "9007199254740992"))
        assertNotNull(err(s, "1e20"))
        assertNotNull(err(s, "1.5"))
        assertNotNull(err(s, "\"3\""))
        assertNull(err("""{"type":"number"}""", "1e20"))
        assertNull(err("""{"type":["string","null"]}""", "null"))
        assertNotNull(err("""{"type":["string","null"]}""", "1"))
    }

    @Test
    fun objectKeywords() {
        val s = """{"type":"object","required":["a"],"properties":{"a":{"type":"string"}},
            "additionalProperties":{"type":"integer"},"propertyNames":{"pattern":"^[a-z]+$"},
            "minProperties":2,"dependentRequired":{"b":["c"]}}"""
        assertNull(err(s, """{"a":"x","c":1}"""))
        assertEquals("", err(s, """{"c":1}""")!!.pointer) // required
        assertEquals("/a", err(s, """{"a":1,"c":1}""")!!.pointer)
        assertEquals("/c", err(s, """{"a":"x","c":"no"}""")!!.pointer) // additionalProperties schema
        assertEquals("/Z", err(s, """{"a":"x","Z":1}""")!!.pointer) // propertyNames
        assertEquals("", err(s, """{"a":"x"}""")!!.pointer) // minProperties
        assertEquals("", err(s, """{"a":"x","b":1}""")!!.pointer) // dependentRequired
        assertEquals("/x~1y~0z", err("""{"additionalProperties":false}""", """{"x/y~z":1}""")!!.pointer)
    }

    @Test
    fun stringKeywordsCountCodePoints() {
        val s = """{"type":"string","minLength":2,"maxLength":2,"pattern":"^\\S+$"}"""
        assertNull(err(s, "\"😀😀\"")) // 4 UTF-16 units, 2 code points
        assertNotNull(err(s, "\"😀\""))
        assertNotNull(err(s, "\"a b\""))
        // pattern is a search, not anchored, unless the pattern anchors itself
        assertNull(err("""{"pattern":"b"}""", "\"abc\""))
    }

    @Test
    fun equalityConstEnumUniqueItems() {
        assertNull(err("""{"const":{"a":[1,{"b":2}]}}""", """{"a":[1.0,{"b":2}]}"""))
        assertNotNull(err("""{"const":1}""", "\"1\""))
        assertNull(err("""{"enum":["x",2]}""", "2.0"))
        assertNotNull(err("""{"enum":["x",2]}""", "3"))
        assertEquals("", err("""{"uniqueItems":true}""", """[{"a":1,"b":2},{"b":2,"a":1.0}]""")!!.pointer)
        assertNull(err("""{"uniqueItems":true}""", """[1,"1",[1]]"""))
    }

    @Test
    fun combinators() {
        val any = """{"anyOf":[{"type":"object","properties":{"n":{"type":"integer"}}},{"type":"null"}]}"""
        assertNull(err(any, "null"))
        assertEquals("/n", err(any, """{"n":1.5}""")!!.pointer) // deepest branch error
        assertEquals("", err(any, "1")!!.pointer)
        val one = """{"oneOf":[{"type":"integer"},{"type":"number"}]}"""
        assertNull(err(one, "1.5"))
        assertNotNull(err(one, "1")) // matches both
        val cond = """{"if":{"properties":{"t":{"const":"a"}},"required":["t"]},"then":{"required":["x"]},"else":{"properties":{"x":false}}}"""
        assertNull(err(cond, """{"t":"a","x":1}"""))
        assertNotNull(err(cond, """{"t":"a"}"""))
        assertEquals("/x", err(cond, """{"t":"b","x":1}""")!!.pointer)
        assertNull(err(cond, """{"t":"b"}"""))
        assertNull(err("true", "1"))
        assertNotNull(err("false", "1"))
    }

    @Test
    fun refsResolveAcrossFiles() {
        assertNull(SyncSchemas.validate("common.schema.json#/\$defs/uuid", JsonString("0193a1b2-0001-7000-8000-000000000001")))
        assertNotNull(SyncSchemas.validate("common.schema.json#/\$defs/uuid", JsonString("0193A1B2-0001-7000-8000-000000000001")))
        assertNull(SyncSchemas.validate("common.schema.json#/\$defs/item_key", JsonString("e:1358280")))
        assertNotNull(SyncSchemas.validate("common.schema.json#/\$defs/item_key", JsonString("e:01")))
        // ops/*.schema.json refer to ../common.schema.json
        assertNotNull(SyncSchemas.validate("ops/note.deleted.schema.json", v("""{"id":"x"}""")))
        assertFailsWith<IllegalArgumentException> { SyncSchemas.validate("nope.schema.json", v("1")) }
        assertFailsWith<IllegalArgumentException> { SyncSchemas.validate("op.schema.json#/nope", v("1")) }
    }
}
