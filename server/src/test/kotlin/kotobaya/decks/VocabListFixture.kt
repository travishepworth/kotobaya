package kotobaya.decks

import org.jooq.DSLContext

/**
 * The rows of contracts/db/VOCAB_LISTS.md 6 (= contracts/db/test/smoke_v3.sql): release 2 current, its entries, and
 * the loader's output for the example n5/n4 files.
 */
object VocabListFixture {
    /** jlpt-n5 in deck-build order (4.3): 十 (120), 食べる (250), 会う (300), 青 (null, row 2), じゃ (null, row 5). */
    val N5_ORDER = listOf(1579840L, 1358280L, 1198180L, 1381380L, 1005900L)

    fun load(dsl: DSLContext) {
        dsl.execute(
            "insert into ref.dict_release (id, version, built_at, is_current, sources) values " +
                "(1, '2026.11.0', '2026-11-01T00:00:00Z', false, '{}'), (2, '2026.11.1', '2026-11-14T00:00:00Z', true, '{}')",
        )
        dsl.execute(
            "insert into ref.dict_entry (seq, headword, headword_reading, kanji_forms, readings, senses, freq_rank, added_release_id, retired_release_id) values " +
                "(1198180, '会う', 'あう', '[{\"text\":\"会う\"}]', '[{\"text\":\"あう\"}]', '[]', 300, 1, null), " +
                "(1381380, '青', 'あお', '[{\"text\":\"青\"}]', '[{\"text\":\"あお\"}]', '[]', null, 1, null), " +
                "(1579840, '十', 'じゅう', '[{\"text\":\"十\"}]', '[{\"text\":\"じゅう\"},{\"text\":\"とお\"}]', '[]', 120, 1, null), " +
                "(1005900, 'じゃ', 'じゃ', '[]', '[{\"text\":\"じゃ\"},{\"text\":\"じゃあ\"}]', '[]', null, 1, null), " +
                "(1358280, '食べる', 'たべる', '[{\"text\":\"食べる\"}]', '[{\"text\":\"たべる\"}]', '[]', 250, 1, null), " +
                "(1578850, '行く', 'いく', '[{\"text\":\"行く\"}]', '[{\"text\":\"いく\"},{\"text\":\"ゆく\"}]', '[]', 80, 1, null), " +
                "(1000010, '行く', 'いく', '[{\"text\":\"行く\"}]', '[{\"text\":\"いく\"}]', '[]', null, 1, 2), " +
                "(9990001, '乾', 'かん', '[{\"text\":\"乾\"}]', '[{\"text\":\"かん\"}]', '[]', null, 1, null), " +
                "(9990002, '乾', 'かん', '[{\"text\":\"乾\"}]', '[{\"text\":\"かん\"}]', '[]', null, 1, null)",
        )
        dsl.execute(
            "insert into ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows) values " +
                "('jlpt-n5', 'jlpt', 0, 'JLPT N5', 2, 5, 6, 1, 0, 0), ('jlpt-n4', 'jlpt', 1, 'JLPT N4', 2, 1, 5, 0, 1, 3)",
        )
        dsl.execute(
            "insert into ref.vocab_list_item (list_id, seq, position) values ('jlpt-n5', 1198180, 1), ('jlpt-n5', 1381380, 2), " +
                "('jlpt-n5', 1579840, 3), ('jlpt-n5', 1005900, 5), ('jlpt-n5', 1358280, 6), ('jlpt-n4', 1578850, 2)",
        )
        dsl.execute(
            "insert into ref.vocab_list_unmatched (list_id, source_row, jmdict_seq, kana, kanji, reason, candidates) values " +
                "('jlpt-n4', 3, null, 'かん', '乾', 'ambiguous', '{9990001,9990002}'), ('jlpt-n4', 4, 9999999, 'ほげ', '保下', 'no_entry', '{}'), " +
                "('jlpt-n4', 5, null, 'あ', '亜', 'invalid_row', '{}')",
        )
    }

    /** A one-item vocabulary list [id] (any family member) for tests that only need the key to be offered. */
    fun oneItemList(dsl: DSLContext, id: String, seq: Long, ordinal: Int = 2) {
        dsl.execute(
            "insert into ref.vocab_list (id, family, ordinal, title, release_id, entry_count, source_rows, duplicate_rows, shadowed_rows, unmatched_rows) " +
                "values (?, 'jlpt', ?, ?, (select max(id) from ref.dict_release), 1, 1, 0, 0, 0)",
            id, ordinal, id.uppercase().replace('-', ' '),
        )
        dsl.execute("insert into ref.vocab_list_item (list_id, seq, position) values (?, ?, 1)", id, seq)
    }
}
