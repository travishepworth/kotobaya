"""Read the rows of one device package out of the server's ``ref.*`` tables."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

import psycopg

from .sqlite import Row

TOP_N_DEFAULT = 60_000
SCOPES = ("top60k", "all")

# Queries run against the temp table _sel(seq) holding the entries of the package.
_ROW_QUERIES: dict[str, str] = {
    "dict_entry": """
        SELECT e.seq, e.language, e.headword, e.headword_reading, e.is_common::int,
               e.kanji_forms::text, e.readings::text, e.senses::text,
               to_jsonb(e.pos)::text, to_jsonb(e.misc)::text, e.freq_rank,
               (e.retired_release_id IS NOT NULL)::int
        FROM ref.dict_entry e JOIN _sel USING (seq)""",
    "dict_entry_form": """
        SELECT f.seq, f.kind, f.ord, f.form, f.norm, f.common::int
        FROM ref.dict_entry_form f JOIN _sel USING (seq)""",
    "dict_entry_gloss": """
        SELECT g.seq, g.sense_ord, g.gloss_ord, g.lang, g.gloss, g.gloss
        FROM ref.dict_entry_gloss g JOIN _sel USING (seq)""",  # last column lowered in Python
    "entry_kanji": "SELECT k.seq, k.literal FROM ref.entry_kanji k JOIN _sel USING (seq)",
    "freq_list": "SELECT id, title, priority FROM ref.freq_list",
    "entry_freq": """
        SELECT f.seq, f.list_id, f.rank FROM ref.entry_freq f JOIN _sel USING (seq)""",
    "entry_pitch": """
        SELECT p.seq, p.reading, to_jsonb(p.positions)::text
        FROM ref.entry_pitch p JOIN _sel USING (seq)""",
    "kanji": """
        SELECT literal, to_jsonb(on_readings)::text, to_jsonb(kun_readings)::text,
               to_jsonb(nanori)::text, to_jsonb(meanings)::text, stroke_count, grade, jlpt,
               freq_rank, radical, to_jsonb(components)::text
        FROM ref.kanji""",
    "example_sentence": """
        SELECT id, language, text, translation, translation_id FROM ref.example_sentence
        WHERE id IN (SELECT x.sentence_id FROM ref.example_sentence_entry x JOIN _sel USING (seq))""",
    "example_sentence_entry": """
        SELECT x.sentence_id, x.seq FROM ref.example_sentence_entry x JOIN _sel USING (seq)""",
}


def read_rows(
    conn: psycopg.Connection[Any],
    release_id: int,
    scope: str,
    referenced: Iterable[int] = (),
    top_n: int = TOP_N_DEFAULT,
) -> tuple[dict[str, list[Row]], dict[str, int]]:
    """Rows per device table for ``scope``, plus the row counts Postgres says there should be.

    ``top60k``: live entries with ``freq_rank <= top_n`` plus every ``referenced`` seq that exists
    (retired ones flagged ``retired = 1``). ``all``: every entry. The expected counts come from
    separate ``count(*)`` queries so the caller can prove nothing was dropped on the way.
    """
    if scope not in SCOPES:
        raise ValueError(f"unknown scope {scope!r}")
    refs = sorted(set(referenced))
    with conn.cursor() as cur:
        cur.execute("CREATE TEMP TABLE _sel (seq bigint PRIMARY KEY)")
        if scope == "all":
            cur.execute("INSERT INTO _sel SELECT seq FROM ref.dict_entry")
        else:
            cur.execute(
                """INSERT INTO _sel SELECT seq FROM ref.dict_entry
                   WHERE (retired_release_id IS NULL AND freq_rank <= %s) OR seq = ANY(%s)""",
                (top_n, refs),
            )
        cur.execute(
            """SELECT id, version, (extract(epoch FROM built_at) * 1000)::bigint, %s, sources::text
               FROM ref.dict_release WHERE id = %s""",
            (scope, release_id),
        )
        release = cur.fetchone()
        if release is None:
            raise ValueError(f"ref.dict_release {release_id} not found")
        rows: dict[str, list[Row]] = {"dict_release": [tuple(release)]}
        expected: dict[str, int] = {"dict_release": 1}
        for table, query in _ROW_QUERIES.items():
            cur.execute(query)
            data = [tuple(r) for r in cur.fetchall()]
            if table == "dict_entry_gloss":
                data = [(*r[:5], r[4].lower()) for r in data]
            rows[table] = data
            cur.execute(f"SELECT count(*) FROM ({query}) q")
            one = cur.fetchone()
            assert one is not None
            expected[table] = one[0]
        cur.execute("DROP TABLE _sel")
    return rows, expected
