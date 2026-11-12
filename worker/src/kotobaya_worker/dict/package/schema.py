"""Device dictionary schema: a copy of ``contracts/db/sqldelight/ref/.../Dict.sq`` (type-erased).

A test compares the sqlite_master of a file built from this with one built from ``Dict.sq``.
"""

from __future__ import annotations

from dataclasses import dataclass

USER_VERSION = 1


@dataclass(frozen=True)
class Table:
    name: str
    columns: tuple[str, ...]
    pk: tuple[str, ...]


# Order: parents before children.
TABLES: tuple[Table, ...] = (
    Table("dict_release", ("id", "version", "built_at", "scope", "sources"), ("id",)),
    Table(
        "dict_entry",
        (
            "seq",
            "language",
            "headword",
            "headword_reading",
            "is_common",
            "kanji_forms",
            "readings",
            "senses",
            "pos",
            "misc",
            "freq_rank",
            "retired",
        ),
        ("seq",),
    ),
    Table(
        "dict_entry_form",
        ("seq", "kind", "ord", "form", "norm", "common"),
        ("seq", "kind", "ord"),
    ),
    Table(
        "dict_entry_gloss",
        ("seq", "sense_ord", "gloss_ord", "lang", "gloss", "gloss_lower"),
        ("seq", "sense_ord", "gloss_ord"),
    ),
    Table("entry_kanji", ("seq", "literal"), ("seq", "literal")),
    Table("freq_list", ("id", "title", "priority"), ("id",)),
    Table("entry_freq", ("seq", "list_id", "rank"), ("seq", "list_id")),
    Table("entry_pitch", ("seq", "reading", "positions"), ("seq", "reading")),
    Table(
        "kanji",
        (
            "literal",
            "on_readings",
            "kun_readings",
            "nanori",
            "meanings",
            "stroke_count",
            "grade",
            "jlpt",
            "freq_rank",
            "radical",
            "components",
        ),
        ("literal",),
    ),
    Table(
        "example_sentence",
        ("id", "language", "text", "translation", "translation_id"),
        ("id",),
    ),
    Table("example_sentence_entry", ("sentence_id", "seq"), ("sentence_id", "seq")),
)
TABLE_BY_NAME = {t.name: t for t in TABLES}

DDL = """
CREATE TABLE dict_release (
  id INTEGER NOT NULL PRIMARY KEY,
  version TEXT NOT NULL,
  built_at INTEGER NOT NULL,
  scope TEXT NOT NULL,
  sources TEXT NOT NULL
);
CREATE TABLE dict_entry (
  seq INTEGER NOT NULL PRIMARY KEY,
  language TEXT NOT NULL DEFAULT 'ja',
  headword TEXT NOT NULL,
  headword_reading TEXT NOT NULL,
  is_common INTEGER NOT NULL DEFAULT 0,
  kanji_forms TEXT NOT NULL,
  readings TEXT NOT NULL,
  senses TEXT NOT NULL,
  pos TEXT NOT NULL,
  misc TEXT NOT NULL,
  freq_rank INTEGER,
  retired INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX dict_entry_freq_rank_idx ON dict_entry (freq_rank);
CREATE TABLE dict_entry_form (
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  kind TEXT NOT NULL,
  ord INTEGER NOT NULL,
  form TEXT NOT NULL,
  norm TEXT NOT NULL,
  common INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (seq, kind, ord)
);
CREATE INDEX dict_entry_form_norm_idx ON dict_entry_form (norm);
CREATE TABLE dict_entry_gloss (
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  sense_ord INTEGER NOT NULL,
  gloss_ord INTEGER NOT NULL,
  lang TEXT NOT NULL DEFAULT 'eng',
  gloss TEXT NOT NULL,
  gloss_lower TEXT NOT NULL,
  PRIMARY KEY (seq, sense_ord, gloss_ord)
);
CREATE INDEX dict_entry_gloss_lower_idx ON dict_entry_gloss (gloss_lower);
CREATE TABLE entry_kanji (
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  literal TEXT NOT NULL,
  PRIMARY KEY (seq, literal)
);
CREATE INDEX entry_kanji_literal_idx ON entry_kanji (literal);
CREATE TABLE freq_list (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  priority INTEGER NOT NULL
);
CREATE TABLE entry_freq (
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  list_id TEXT NOT NULL REFERENCES freq_list (id),
  rank INTEGER NOT NULL,
  PRIMARY KEY (seq, list_id)
);
CREATE TABLE entry_pitch (
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  reading TEXT NOT NULL,
  positions TEXT NOT NULL,
  PRIMARY KEY (seq, reading)
);
CREATE TABLE kanji (
  literal TEXT NOT NULL PRIMARY KEY,
  on_readings TEXT NOT NULL,
  kun_readings TEXT NOT NULL,
  nanori TEXT NOT NULL,
  meanings TEXT NOT NULL,
  stroke_count INTEGER,
  grade INTEGER,
  jlpt INTEGER,
  freq_rank INTEGER,
  radical TEXT,
  components TEXT NOT NULL
);
CREATE TABLE example_sentence (
  id INTEGER NOT NULL PRIMARY KEY,
  language TEXT NOT NULL DEFAULT 'ja',
  text TEXT NOT NULL,
  translation TEXT NOT NULL,
  translation_id INTEGER
);
CREATE TABLE example_sentence_entry (
  sentence_id INTEGER NOT NULL REFERENCES example_sentence (id),
  seq INTEGER NOT NULL REFERENCES dict_entry (seq),
  PRIMARY KEY (sentence_id, seq)
);
CREATE INDEX example_sentence_entry_seq_idx ON example_sentence_entry (seq);
"""
