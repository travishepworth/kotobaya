package kotobaya.android.dict

/** One table of `contracts/db/sqldelight/ref/.../Dict.sq` (DICT_PACKAGE 3.3). A test checks this against the real schema. */
internal class DictTable(val name: String, val columns: List<String>, val pk: List<String>)

internal object DictSchema {
    const val USER_VERSION = 1
    const val DIFF_FORMAT = "kotobaya-dict-diff"
    const val DIFF_VERSION = 1

    /** Canonical order: parents before children (the apply order). */
    val tables: List<DictTable> = listOf(
        DictTable("dict_release", listOf("id", "version", "built_at", "scope", "sources"), listOf("id")),
        DictTable(
            "dict_entry",
            listOf(
                "seq", "language", "headword", "headword_reading", "is_common", "kanji_forms", "readings",
                "senses", "pos", "misc", "freq_rank", "retired",
            ),
            listOf("seq"),
        ),
        DictTable("dict_entry_form", listOf("seq", "kind", "ord", "form", "norm", "common"), listOf("seq", "kind", "ord")),
        DictTable(
            "dict_entry_gloss",
            listOf("seq", "sense_ord", "gloss_ord", "lang", "gloss", "gloss_lower"),
            listOf("seq", "sense_ord", "gloss_ord"),
        ),
        DictTable("entry_kanji", listOf("seq", "literal"), listOf("seq", "literal")),
        DictTable("freq_list", listOf("id", "title", "priority"), listOf("id")),
        DictTable("entry_freq", listOf("seq", "list_id", "rank"), listOf("seq", "list_id")),
        DictTable("entry_pitch", listOf("seq", "reading", "positions"), listOf("seq", "reading")),
        DictTable(
            "kanji",
            listOf(
                "literal", "on_readings", "kun_readings", "nanori", "meanings", "stroke_count", "grade", "jlpt",
                "freq_rank", "radical", "components",
            ),
            listOf("literal"),
        ),
        DictTable(
            "example_sentence",
            listOf("id", "language", "text", "translation", "translation_id"),
            listOf("id"),
        ),
        DictTable("example_sentence_entry", listOf("sentence_id", "seq"), listOf("sentence_id", "seq")),
    )
}
