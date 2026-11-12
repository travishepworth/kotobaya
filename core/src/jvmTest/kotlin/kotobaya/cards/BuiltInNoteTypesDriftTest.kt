package kotobaya.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInNoteTypesDriftTest {
    @Test
    fun builtInsEqualV1Seeds() {
        val sql = File("../contracts/db/V1__init.sql").readText()
        val start = sql.indexOf("INSERT INTO app.note_type")
        val seeds = sql.substring(start, sql.indexOf(";\n", start))
        val rows = Regex("""\('([0-9a-f-]{36})', '(\w+)', '(\w+)', true,\s*'(\[.*?\])',\s*'(\{.*?\})',""")
            .findAll(seeds).toList()
        assertEquals(BuiltInNoteTypes.ALL.size, rows.size)
        for ((row, bt) in rows.zip(BuiltInNoteTypes.ALL)) {
            val (id, name, base, fields, templates) = row.destructured
            assertEquals(bt.id, id)
            assertEquals(bt.name, name)
            assertEquals(bt.info.baseType.wire, base)
            val seedFields = Regex("""\{"key":"(\w+)","label":"([^"]*)","kind":"(\w+)"\}""")
                .findAll(fields).map { FieldDef(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
            assertEquals(bt.fields, seedFields, name)
            val seedTemplates = Regex("""\"(\w+)":\{"default":(true|false),"front":null,"back":null\}""")
                .findAll(templates).map { it.groupValues[1] to (it.groupValues[2] == "true") }.toList()
            assertEquals(bt.info.templates.map { it.kind to it.isDefault }, seedTemplates, name)
            assertEquals(true, bt.info.templates.all { it.front == null && it.back == null })
        }
    }
}
