package kotobaya.search.sql

import kotobaya.contracts.queue.StudyDay
import kotobaya.search.DefaultSearchQueryParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Runs the compiled SQL against real databases loaded from the contracts: SQLite (device schema,
 * `contracts/db/sqldelight/app`, residual predicate applied) and Postgres 17 (`contracts/db/V1__init.sql`,
 * Testcontainers; skipped with a message when Docker is unavailable). Both must select exactly the
 * hand-written card sets, which also makes them equal to each other.
 */
class SearchSqlExecutionTest {
    @Test
    fun sqliteSelectsTheExpectedCards() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // the on-device dictionary is a separate SQLite file (Dict.sq), reached through SearchContext.dictForms
        val dict = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use {
            dict.use { d ->
                loadSqlite(it, "app")
                insertFixture(it, Dialect.SQLITE)
                loadSqlite(d, "ref")
                d.createStatement().use { st -> for (s in dictStatements(pg = false)) st.execute(s) }
                check(it, Dialect.SQLITE, CONTEXT.copy(dictForms = { q -> lookupForms(d, q) }))
            }
        }
    }

    @Test
    fun sqliteWithoutInstalledDictionaryMatchesNoteTextOnly() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use {
            loadSqlite(it, "app")
            insertFixture(it, Dialect.SQLITE)
            for ((query, expected) in listOf("食べる" to setOf(1, 2, 26, 10), "たべる" to setOf(1, 2, 26), "シャツ" to emptySet())) {
                val node = assertNotNull(DefaultSearchQueryParser.parse(query).query, query)
                assertEquals(expected.map(::cardId).toSet(), run(it, SearchSqlCompiler.compile(node, Dialect.SQLITE, CONTEXT)), query)
            }
        }
    }

    private fun lookupForms(dict: Connection, q: SqlFragment): List<DictFormRow> {
        val out = ArrayList<DictFormRow>()
        dict.prepareStatement(q.sql).use { st ->
            q.params.forEachIndexed { i, p -> st.setString(i + 1, p as String) }
            st.executeQuery().use { rs -> while (rs.next()) out.add(DictFormRow(rs.getLong(1), rs.getString(2), rs.getString(3))) }
        }
        return out
    }

    @Test
    fun postgresSelectsTheExpectedCards() {
        val pg = PgHolder.connectionOrNull()
        if (pg == null) {
            println("SKIPPED postgresSelectsTheExpectedCards: Docker is not available (${PgHolder.reason})")
            return
        }
        pg.use {
            insertFixture(it, Dialect.POSTGRES)
            it.createStatement().use { st -> for (s in dictStatements(pg = true)) st.execute(s) }
            check(it, Dialect.POSTGRES, CONTEXT)
        }
    }

    private fun check(conn: Connection, dialect: Dialect, context: SearchContext) {
        val failures = ArrayList<String>()
        for ((query, expected) in EXPECTED) {
            val node = assertNotNull(DefaultSearchQueryParser.parse(query).query, query)
            val compiled = SearchSqlCompiler.compile(node, dialect, context)
            val got = run(conn, compiled)
            val want = expected.map(::cardId).toSet()
            if (got != want) {
                failures.add(
                    "<$query> $dialect: missing ${(want - got).map(::cardNo)} extra ${(got - want).map(::cardNo)}",
                )
            }
        }
        assertEquals(emptyList(), failures)
    }

    private fun run(conn: Connection, c: CompiledSearch): Set<String> {
        val f = c.select()
        val out = LinkedHashSet<String>()
        conn.prepareStatement(f.sql).use { st ->
            f.params.forEachIndexed { i, p ->
                when (p) {
                    is String -> st.setString(i + 1, p)
                    is Long -> st.setLong(i + 1, p)
                    is Double -> st.setDouble(i + 1, p)
                    else -> error("unexpected param type ${p::class}")
                }
            }
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getString(1)
                    if (!c.residual) {
                        out.add(id)
                    } else {
                        val probes = (0 until c.probes.size).map { rs.getInt(8 + it) != 0 }
                        val row = ResidualRow(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), probes)
                        if (c.accepts(row)) out.add(id)
                    }
                }
            }
        }
        return out
    }
}

private object PgHolder {
    var reason: String = ""
    private val container: Any? by lazy {
        try {
            if (!org.testcontainers.DockerClientFactory.instance().isDockerAvailable) {
                reason = "isDockerAvailable=false"
                null
            } else {
                org.testcontainers.containers.PostgreSQLContainer("postgres:17")
                    .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C.UTF-8")
                    .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw")) // no anonymous volume per run (D-2)
                    .also { it.start() }
            }
        } catch (e: Throwable) {
            reason = e.toString()
            null
        }
    }

    fun connectionOrNull(): Connection? {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) // the container rejects some host zone names
        val c = container as org.testcontainers.containers.PostgreSQLContainer<*>? ?: return null
        val url = c.jdbcUrl
        // simple query mode lets the driver send the multi-statement migration unsplit
        DriverManager.getConnection(url + (if (url.contains("?")) "&" else "?") + "preferQueryMode=simple", c.username, c.password).use { loader ->
            loader.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS app CASCADE; DROP SCHEMA IF EXISTS ref CASCADE")
                st.execute(File("../contracts/db/V1__init.sql").readText())
            }
        }
        val conn = DriverManager.getConnection(url, c.username, c.password)
        return conn
    }
}

// ---------------------------------------------------------------------------------------------
// Schema loading
// ---------------------------------------------------------------------------------------------

private fun loadSqlite(conn: Connection, db: String) {
    val dir = File("../contracts/db/sqldelight/$db")
    val text = dir.walkTopDown().filter { it.extension == "sq" }.sortedBy { it.path }.joinToString("\n") { it.readText() }
        .replace(Regex(" AS kotlin\\.[A-Za-z.]+"), "")
        .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
    conn.createStatement().use { st ->
        for (stmt in splitStatements(text)) st.execute(stmt)
    }
}

/** Splits SQL at `;` outside single-quoted strings. */
private fun splitStatements(sql: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var q = false
    var comment = false
    var prev = ' '
    for (ch in sql) {
        if (comment) {
            if (ch == '\n') comment = false
            prev = ch
            continue
        }
        if (!q && ch == '-' && prev == '-') {
            comment = true
            cur.setLength(cur.length - 1)
            prev = ch
            continue
        }
        prev = ch
        if (ch == '\'') q = !q
        if (ch == ';' && !q) {
            if (cur.isNotBlank()) out.add(cur.toString())
            cur.clear()
        } else {
            cur.append(ch)
        }
    }
    if (cur.isNotBlank()) out.add(cur.toString())
    return out
}

// ---------------------------------------------------------------------------------------------
// Fixture
// ---------------------------------------------------------------------------------------------

private const val S = 1_700_000_000_000L
private const val D = 86_400_000L
private const val H = 3_600_000L
private const val SERVER = "00000000-0000-7000-8000-000000000001"
private const val DEFAULT_PRESET = "00000000-0000-7000-8000-000000000010"
private const val HARD_PRESET = "0193a1b2-0004-7000-8000-000000000001"
private const val HLC = "000000000001-0001-00000000-0000-7000-8000-000000000001"

private fun noteId(n: Int) = "0193a1b2-0001-7000-8000-0000000000" + n.toString().padStart(2, '0')

private fun cardId(n: Int) = "0193a1b2-0002-7000-8000-0000000000" + n.toString().padStart(2, '0')

private fun cardNo(id: String) = "c" + id.takeLast(2)

private fun deckId(n: Int) = "0193a1b2-0003-7000-8000-0000000000" + n.toString().padStart(2, '0')

private val CONTEXT = SearchContext(
    studyDay = StudyDay(S, S + D),
    nowMs = S + 10 * H,
    leechThreshold = 8,
    hotNoteIds = setOf(noteId(5)),
    smartDecks = listOf(SmartDeckRef("Leeches", DefaultSearchQueryParser.parse("is:leech").query!!)),
)

private class Nt(
    val n: Int,
    val type: String,
    val fields: String,
    val tags: List<String> = emptyList(),
    val entry: Long? = null,
    val kanji: String? = null,
    val mnemonic: String = "",
    val source: String = "",
    val deleted: Boolean = false,
    val mergedInto: Int? = null,
)

private class Cd(
    val n: Int,
    val note: Int,
    val kind: String = "meaning",
    val state: String = "new",
    val susp: Boolean = false,
    val buried: Long? = null,
    val flags: Int = 0,
    val stab: Double? = null,
    val diff: Double? = null,
    val due: Long? = null,
    val interval: Int? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val created: Long = S - D,
    val deleted: Boolean = false,
    val mergedInto: Int? = null,
    val preset: String = DEFAULT_PRESET,
)

private class Rv(val n: Int, val card: Int, val at: Long, val type: String, val rating: Int?, val manual: String? = null, val undone: Boolean = false)

private const val VOCAB = "00000000-0000-7000-8000-000000000101"
private const val KANJI = "00000000-0000-7000-8000-000000000102"
private const val PHRASE = "00000000-0000-7000-8000-000000000103"
private const val SENTENCE = "00000000-0000-7000-8000-000000000105"
private const val CUSTOM = "00000000-0000-7000-8000-000000000106"

private fun typeId(t: String) = when (t) {
    "vocab" -> VOCAB
    "kanji" -> KANJI
    "phrase" -> PHRASE
    "sentence" -> SENTENCE
    else -> CUSTOM
}

private val NOTES = listOf(
    Nt(1, "vocab", """{"expression":"食べる","meaning":"to eat","reading":"たべる"}""", listOf("anime", "jlpt_n5"), entry = 1001, mnemonic = "tabby eats"),
    Nt(2, "vocab", """{"expression":"飲む","meaning":"to drink"}""", listOf("anime"), entry = 1002, mnemonic = "Drink up"),
    Nt(3, "vocab", """{"expression":"成績","meaning":"grades","reading":"せいせき"}""", listOf("school"), entry = 1003),
    Nt(4, "vocab", """{"expression":"責任","meaning":"responsibility"}""", entry = 1004),
    Nt(5, "vocab", """{"expression":"走る","meaning":"to run"}""", listOf("anime", "drama"), entry = 1005, source = "I eat and run"),
    Nt(6, "kanji", """{"meaning":"achievement"}""", listOf("jlpt_n1"), kanji = "績"),
    Nt(7, "kanji", """{"meaning":"blame"}""", kanji = "責"),
    Nt(8, "phrase", """{"front":"Eat well","back":"よく食べる"}""", listOf("x y")),
    Nt(9, "vocab", """{"expression":"見る","meaning":"to see"}""", listOf("Anime"), entry = 1009),
    Nt(10, "vocab", """{"expression":"本当","meaning":"100% sure","note":"a_b"}""", entry = 1010),
    Nt(11, "vocab", """{"expression":"行く","meaning":"to go"}""", listOf("jlpt_n5", "jlpt_n4"), entry = 1011),
    Nt(12, "sentence", """{"front":"say \"hi\"","back":"挨拶"}"""),
    Nt(13, "vocab", """{"meaning":"to eat dup"}""", listOf("dupe"), entry = 1001, deleted = true, mergedInto = 1),
    Nt(14, "vocab", """{"meaning":"eat deleted"}""", listOf("anime"), entry = 1014, deleted = true),
    Nt(15, "vocab", """{"expression":"買う","meaning":"to buy"}""", entry = 1015),
    Nt(16, "vocab", """{"expression":"違う","meaning":"to differ"}""", entry = 1016),
    Nt(17, "vocab", """{"expression":"待つ","meaning":"to wait"}""", entry = 1017),
    Nt(18, "vocab", """{"expression":"言う","meaning":"to say"}""", entry = 1018),
    Nt(20, "custom", """{"front":"abc","back":"def"}""", listOf("anime")),
    // C-24: entry-linked vocab notes with empty fields (dictionary, jpdb import, deck-build); their text is the entry's forms
    Nt(21, "vocab", "{}", entry = 1021),
    Nt(22, "vocab", "{}", entry = 1022),
    Nt(23, "vocab", "{}", entry = 1023), // entry retired: no form rows
)

/** `dict_entry_form` rows (seq, kind, ord, form, norm); 1023 is retired, so it has none. 1014 belongs to a deleted note. */
private val FORMS = listOf(
    listOf<Any>(1001L, "kanji", 0, "食べる", "食べる"),
    listOf<Any>(1001L, "reading", 0, "たべる", "たべる"),
    listOf<Any>(1014L, "kanji", 0, "食べる", "食べる"),
    listOf<Any>(1021L, "kanji", 0, "食べる", "食べる"),
    listOf<Any>(1021L, "reading", 0, "たべる", "たべる"),
    listOf<Any>(1022L, "kanji", 0, "Ｔシャツ", "Tしゃつ"),
    listOf<Any>(1022L, "reading", 0, "ティーシャツ", "てぃーしゃつ"),
)

private fun dictStatements(pg: Boolean): List<String> {
    val g = Gen(pg)
    val r = if (pg) "ref." else ""
    val out = ArrayList<String>()
    out.add(
        if (pg) {
            "INSERT INTO ref.dict_release (id, version, built_at, is_current) VALUES (1, 't1', ${g.ts(S)}, true)"
        } else {
            "INSERT INTO dict_release (id, version, built_at, scope, sources) VALUES (1, 't1', $S, 'all', '{}')"
        },
    )
    for (seq in listOf(1001L, 1014L, 1021L, 1022L, 1023L)) {
        val head = FORMS.firstOrNull { it[0] == seq }?.get(3) as String? ?: "x"
        out.add(
            if (pg) {
                "INSERT INTO ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id, retired_release_id) VALUES " +
                    "($seq, ${g.q(head)}, 'x', '[{\"text\":\"x\"}]'::jsonb, '[]'::jsonb, 1, ${if (seq == 1023L) "1" else "NULL"})"
            } else {
                "INSERT INTO dict_entry (seq, headword, headword_reading, kanji_forms, readings, senses, pos, misc, retired) VALUES " +
                    "($seq, ${g.q(head)}, 'x', '[]', '[]', '[]', '[]', '[]', ${if (seq == 1023L) 1 else 0})"
            },
        )
    }
    for (f in FORMS) {
        out.add("INSERT INTO ${r}dict_entry_form (seq, kind, ord, form, norm) VALUES (${f[0]}, ${g.q(f[1] as String)}, ${f[2]}, ${g.q(f[3] as String)}, ${g.q(f[4] as String)})")
    }
    return out
}

private val CARDS = listOf(
    Cd(1, 1, state = "review", lapses = 1, reps = 5, stab = 20.0, diff = 5.0, due = S - D + 6 * H, interval = 20, created = S - 100 * D),
    Cd(2, 1, "reading", "review", reps = 3, stab = 8.0, diff = 4.0, due = S + 5 * H, interval = 8, created = S - 30 * D),
    Cd(3, 2, state = "learning", flags = 3, lapses = 2, reps = 2, stab = 1.5, diff = 6.0, due = S + 3 * H, interval = 0, created = S + H),
    Cd(4, 2, "reading", created = S - D),
    Cd(5, 3, state = "review", lapses = 9, reps = 20, stab = 3.0, diff = 8.0, due = S + 2 * D + H, interval = 3, created = S - 200 * D),
    Cd(6, 4, state = "relearning", buried = S + 20 * H, lapses = 4, reps = 8, stab = 2.0, diff = 7.0, due = S + H, interval = 1, created = S - 50 * D),
    Cd(7, 5, state = "review", reps = 4, stab = 40.5, diff = 3.0, due = S + D + 2 * H, interval = 40, created = S - 60 * D),
    Cd(8, 6, state = "review", reps = 1, stab = 11.0, diff = 3.0, due = S + 3 * D, interval = 21, created = S - 10 * D),
    Cd(9, 7, created = S - 3 * D),
    Cd(10, 8, created = S + 2 * H),
    Cd(11, 9, state = "review", susp = true, lapses = 3, reps = 6, stab = 15.0, diff = 5.0, due = S - 3 * D, interval = 15, created = S - 20 * D),
    Cd(12, 10, state = "review", reps = 2, stab = 4.0, diff = 5.0, due = S + 8 * H, interval = 4, created = S - 5 * D),
    Cd(13, 11, state = "review", reps = 7, stab = 30.0, diff = 2.0, due = S + 4 * D + 3 * H, interval = 30, created = S - 90 * D),
    Cd(14, 12, created = S - 2 * D),
    Cd(15, 13, deleted = true, mergedInto = 1, created = S - 10 * D),
    Cd(16, 13, "reading", deleted = true, mergedInto = 2, created = S - 10 * D),
    Cd(17, 14),
    Cd(18, 15, state = "review", susp = true, reps = 2, stab = 5.0, diff = 5.0, due = S - 12 * H, interval = 5, created = S - 4 * D),
    Cd(19, 16),
    Cd(20, 17, state = "review", flags = 1, reps = 9, stab = 50.0, diff = 2.0, due = S + 9 * H, interval = 50, created = S - 300 * D),
    Cd(21, 18, state = "learning", reps = 1, stab = 0.5, diff = 6.0, due = S + 2 * H, interval = 0, created = S - H),
    Cd(22, 20, created = S + 5 * H),
    Cd(23, 5, "reading", "review", lapses = 8, reps = 10, stab = 6.0, diff = 9.0, due = S - 5 * D, interval = 6, created = S - 60 * D),
    Cd(24, 11, "reading", "review", reps = 3, stab = 10.0, diff = 4.0, due = S + 10 * D, interval = 10, created = S - 90 * D, preset = HARD_PRESET),
    Cd(25, 3, "reading", deleted = true),
    Cd(26, 1, "listening", created = S - 29 * D),
    Cd(27, 21),
    Cd(28, 22),
    Cd(29, 23),
)

private val REVIEWS = listOf(
    Rv(1, 1, S + H, "scheduled", 1),
    Rv(2, 15, S - 2 * D + 3 * H, "scheduled", 3), // a card merged into c01
    Rv(3, 2, S - 5 * D, "learning", 4),
    Rv(4, 3, S + 2 * H, "scheduled", 2, undone = true),
    Rv(5, 5, S + 3 * H, "manual", null, manual = "reset"),
    Rv(6, 7, S - D + 2 * H, "scheduled", 3),
    Rv(7, 20, S + 4 * H, "scheduled", 3),
)

private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private class Gen(val pg: Boolean) {
    fun q(s: String) = "'" + s.replace("'", "''") + "'"

    fun ts(ms: Long?) = if (ms == null) "NULL" else if (pg) "to_timestamp(${ms}/1000.0)" else ms.toString()

    fun bool(b: Boolean) = if (pg) (if (b) "true" else "false") else (if (b) "1" else "0")

    fun opt(s: String?) = if (s == null) "NULL" else q(s)

    fun tags(t: List<String>) = if (pg) "ARRAY[" + t.joinToString(",") { q(it) } + "]::text[]" else q("[" + t.joinToString(",") { jsonStr(it) } + "]")

    fun json(s: String) = if (pg) q(s) + "::jsonb" else q(s)

    val t = if (pg) "app." else ""

    fun statements(): List<String> {
        val out = ArrayList<String>()
        out.add("INSERT INTO ${t}fsrs_params (id, name, created_at, updated_at) VALUES (${q(HARD_PRESET)}, 'Hard words', ${ts(S)}, ${ts(S)})")
        for (n in NOTES) {
            out.add(
                "INSERT INTO ${t}note (id, note_type_id, type, entry_seq, kanji_literal, fields, tags, mnemonic, source_sentence, " +
                    "created_by_device_id, created_at, updated_at, deleted_at, merged_into_id) VALUES (${q(noteId(n.n))}, ${q(typeId(n.type))}, ${q(n.type)}, " +
                    "${n.entry ?: "NULL"}, ${opt(n.kanji)}, ${json(n.fields)}, ${tags(n.tags)}, ${q(n.mnemonic)}, ${q(n.source)}, ${q(SERVER)}, " +
                    "${ts(S - 100 * D)}, ${ts(S - 100 * D)}, ${ts(if (n.deleted) S - D else null)}, ${opt(n.mergedInto?.let(::noteId))})",
            )
        }
        for (c in CARDS) {
            out.add(
                "INSERT INTO ${t}card (id, note_id, kind, preset_id, suspended, buried_until, flags, state, stability, difficulty, due_at, " +
                    "interval_days, reps, lapses, created_at, updated_at, deleted_at, merged_into_id) VALUES (${q(cardId(c.n))}, ${q(noteId(c.note))}, ${q(c.kind)}, " +
                    "${q(c.preset)}, ${bool(c.susp)}, ${ts(c.buried)}, ${c.flags}, ${q(c.state)}, ${c.stab ?: "NULL"}, ${c.diff ?: "NULL"}, ${ts(c.due)}, " +
                    "${c.interval ?: "NULL"}, ${c.reps}, ${c.lapses}, ${ts(c.created)}, ${ts(c.created)}, ${ts(if (c.deleted) S - D else null)}, " +
                    "${opt(c.mergedInto?.let(::cardId))})",
            )
        }
        for (r in REVIEWS) {
            val id = "0193a1b2-0005-7000-8000-0000000000" + r.n.toString().padStart(2, '0')
            out.add(
                "INSERT INTO ${t}review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating, manual_action, undone_at, undone_hlc) VALUES " +
                    "(${q(id)}, ${q(cardId(r.card))}, ${q(SERVER)}, ${q(HLC)}, ${ts(r.at)}, ${q(r.type)}, ${r.rating ?: "NULL"}, ${opt(r.manual)}, " +
                    "${ts(if (r.undone) r.at + H else null)}, ${if (r.undone) q(HLC) else "NULL"})",
            )
        }
        fun deck(n: Int, name: String, type: String, query: String? = null, deleted: Boolean = false) = out.add(
            "INSERT INTO ${t}deck (id, name, type, query, created_at, updated_at, deleted_at) VALUES (${q(deckId(n))}, ${q(name)}, ${q(type)}, ${opt(query)}, " +
                "${ts(S)}, ${ts(S)}, ${ts(if (deleted) S else null)})",
        )
        fun item(deck: Int, entry: Long? = null, note: Int? = null, pos: Int) = out.add(
            "INSERT INTO ${t}deck_item (deck_id, item_key, entry_seq, note_id, position, added_at) VALUES (${q(deckId(deck))}, " +
                "${q(if (entry != null) "e:$entry" else "n:${noteId(note!!)}")}, ${entry ?: "NULL"}, ${opt(note?.let(::noteId))}, $pos, ${ts(S)})",
        )
        deck(1, "Frieren", "user")
        deck(2, "Anime:S1", "user")
        deck(3, "Leeches", "smart", "is:leech")
        deck(4, "Old", "user", deleted = true)
        deck(5, "Frieren Extra", "user")
        item(1, entry = 1001, pos = 1)
        item(1, entry = 1002, pos = 2)
        item(1, note = 6, pos = 3)
        item(1, note = 13, pos = 4) // a merge loser: resolves to n01
        item(2, entry = 1005, pos = 1)
        item(4, entry = 1011, pos = 1)
        item(5, entry = 1004, pos = 1)
        item(5, entry = 9999, pos = 2) // no live note
        fun ws(seq: Long, status: String, auto: String?, override: String?) = out.add(
            "INSERT INTO ${t}word_status (entry_seq, status, auto_status, override_status, override_hlc, override_origin, updated_at) VALUES " +
                "($seq, ${q(status)}, ${opt(auto)}, ${opt(override)}, ${if (override != null) q(HLC) else "NULL"}, ${if (override != null) q("manual") else "NULL"}, ${ts(S)})",
        )
        ws(1016, "known", null, "known")
        ws(1017, "never_forget", null, "never_forget")
        ws(1018, "learning", "learning", null)
        ws(1010, "new", "new", null)
        return out
    }
}

private fun insertFixture(conn: Connection, dialect: Dialect) {
    conn.createStatement().use { st ->
        for (s in Gen(dialect == Dialect.POSTGRES).statements()) st.execute(s)
    }
}

// ---------------------------------------------------------------------------------------------
// Expectations (card numbers). Live cards: all except c15, c16 (merged), c17 (note deleted), c25 (deleted).
// ---------------------------------------------------------------------------------------------

private val LIVE = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 18, 19, 20, 21, 22, 23, 24, 26, 27, 28, 29)
private val ANIME = setOf(1, 2, 26, 3, 4, 7, 23, 11, 22)
private val NEW = setOf(4, 9, 10, 14, 19, 22, 26, 27, 28, 29)
private val DUE = setOf(1, 2, 3, 12, 20, 21, 23)
private val EAT = setOf(1, 2, 26, 7, 23, 10)
private val FRIEREN = setOf(1, 2, 26, 3, 4, 8)
private val LEECH = setOf(5, 23)
private val KANJI_JI = setOf(8, 5)

private val EXPECTED: List<Pair<String, Set<Int>>> = listOf(
    "" to LIVE,
    "tag:anime" to ANIME,
    "tag:jlpt*" to setOf(1, 2, 26, 8, 13, 24),
    "tag:leech" to LEECH,
    "-tag:anime" to LIVE - ANIME,
    "--tag:anime" to ANIME,
    "eat" to EAT,
    "EAT" to EAT,
    "\"to eat\"" to setOf(1, 2, 26),
    "100%" to setOf(12),
    "a_b" to setOf(12),
    "a_c" to emptySet(),
    "field:meaning:to*" to setOf(1, 2, 26, 3, 4, 7, 23, 11, 13, 24, 18, 19, 20, 21),
    "field:reading:たべる" to setOf(1, 2, 26),
    "-field:reading:*" to emptySet(),
    "field:front:say*" to setOf(14),
    "kanji:績" to KANJI_JI,
    "kanji:責" to setOf(9, 6),
    "is:due" to DUE,
    "is:buried" to setOf(6),
    "is:suspended" to setOf(11, 18),
    "is:leech" to LEECH,
    "is:hot" to setOf(7, 23),
    "is:flagged" to setOf(3, 20),
    "is:new" to NEW,
    "is:learning" to setOf(3, 21),
    "is:relearning" to setOf(6),
    "is:review" to setOf(1, 2, 5, 7, 8, 11, 12, 13, 18, 20, 23, 24),
    "lapses>=8" to LEECH,
    "lapses!=0" to setOf(1, 3, 5, 6, 11, 23),
    "due<=0" to setOf(1, 2, 3, 6, 11, 12, 18, 20, 21, 23),
    "due=1" to setOf(7),
    "due>=3" to setOf(8, 13, 24),
    "due<-1" to setOf(11, 23),
    "due!=0" to setOf(1, 5, 7, 8, 11, 13, 18, 23, 24),
    "interval<21" to setOf(1, 2, 3, 5, 6, 11, 12, 18, 21, 23, 24),
    "stability>10.5" to setOf(1, 7, 8, 11, 13, 20),
    "difficulty<=3" to setOf(7, 8, 13, 20),
    "reps=0" to NEW,
    "rated:1" to setOf(1, 20),
    "rated:1:1" to setOf(1),
    "rated:2" to setOf(1, 20, 7),
    "rated:3:3" to setOf(1, 7, 20),
    "rated:2:3" to setOf(7, 20),
    "rated:7" to setOf(1, 20, 7, 2),
    "added:1" to setOf(3, 10, 22),
    "added:3" to setOf(3, 4, 10, 14, 19, 21, 22, 27, 28, 29),
    "deck:Frieren" to FRIEREN,
    "deck:frieren*" to FRIEREN + 6,
    "deck:\"Anime:S1\"" to setOf(7, 23),
    "deck:Old" to emptySet(),
    "deck:Leeches" to LEECH,
    "deck:LEECH*" to LEECH,
    "kind:reading" to setOf(2, 4, 23, 24),
    "type:kanji" to setOf(8, 9),
    "type:vocab kind:reading" to setOf(2, 4, 23, 24),
    "notetype:kanji" to setOf(8, 9),
    "preset:\"Hard words\"" to setOf(24),
    "preset:default" to LIVE - 24,
    "entry:1005" to setOf(7, 23),
    "nid:${noteId(13)}" to setOf(1, 2, 26),
    "nid:${noteId(1)}" to setOf(1, 2, 26),
    "cid:${cardId(15)}" to setOf(1),
    "cid:${cardId(1)}" to setOf(1),
    "status:known" to setOf(19),
    "status:never_forget" to setOf(20),
    "status:learning" to setOf(21),
    "status:new" to setOf(1, 2, 26, 3, 4, 5, 6, 7, 23, 11, 12, 13, 24, 18, 27, 28, 29),
    "flag:3" to setOf(3),
    "flag:0" to LIVE - setOf(3, 20),
    "tag:anime or is:new" to ANIME + NEW,
    "-(tag:anime or is:new)" to LIVE - ANIME - NEW,
    "tag:anime -is:new" to ANIME - NEW,
    "-tag:anime is:due" to DUE - ANIME,
    "eat or kanji:績" to EAT + KANJI_JI,
    "-eat -tag:anime" to LIVE - EAT - ANIME,
    "(tag:anime or tag:drama) is:due" to ANIME.intersect(DUE),
    "is:hot or is:leech" to setOf(7, 23, 5),
    "-is:hot is:leech" to setOf(5),
    "tag:leech or tag:anime" to ANIME + LEECH,
    "-deck:Frieren is:due" to DUE - FRIEREN,
    "-kanji:績 type:vocab kind:meaning" to setOf(1, 3, 6, 7, 11, 12, 13, 18, 19, 20, 21, 27, 28, 29),
    // C-24: bare terms also match the dictionary forms of entry-linked notes (n21 has empty fields; n14 is
    // deleted; n23's entry is retired and has no forms)
    "食べる" to setOf(1, 2, 26, 10, 27),
    "たべる" to setOf(1, 2, 26, 27),
    "タベル" to setOf(1, 2, 26, 27),
    "ﾀﾍﾞﾙ" to setOf(1, 2, 26, 27),
    "シャツ" to setOf(28),
    "ティーシャツ" to setOf(28),
    "tシャツ" to setOf(28),
    "T*ツ" to setOf(28),
    "\"食べる\" tag:anime" to setOf(1, 2, 26),
    "-食べる type:vocab kind:meaning" to setOf(3, 5, 6, 7, 11, 12, 13, 18, 19, 20, 21, 28, 29),
    "食べる or シャツ" to setOf(1, 2, 26, 10, 27, 28),
)
