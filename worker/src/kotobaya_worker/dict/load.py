"""Load one dictionary release into ``ref.*`` (contracts/db/SCHEMA.md 4.5).

One transaction per release: stage into temp tables with COPY, upsert by natural key, retire
entries that vanished, flip ``is_current``. ``freq_rank`` on entries is never touched (T102).
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import asdict
from datetime import datetime
from itertools import batched
from typing import Any

import psycopg

from .models import Entry, Form, Kanji, LoadResult, ReleaseInfo
from .normalize import is_kanji, normalize_form

_LOCK_KEY = 0x4B4F544F  # advisory lock: one dict load at a time


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _union(seqs: Iterable[tuple[str, ...]]) -> list[str]:
    seen: dict[str, None] = {}
    for tags in seqs:
        for t in tags:
            seen.setdefault(t, None)
    return list(seen)


def _form_json(f: Form, *, reading: bool) -> dict[str, Any]:
    d: dict[str, Any] = {"text": f.text, "common": f.common, "tags": list(f.tags)}
    if reading:
        d["applies_to"] = list(f.applies_to)
    return d


def entry_row(e: Entry) -> tuple[Any, ...]:
    if not e.readings:
        raise ValueError(f"entry {e.seq} has no reading")
    headword = e.kanji[0].text if e.kanji else e.readings[0].text
    is_common = any(f.common for f in (*e.kanji, *e.readings))
    senses = [
        {
            "pos": list(s.pos),
            "misc": list(s.misc),
            "field": list(s.field),
            "dialect": list(s.dialect),
            "info": list(s.info),
            "applies_to_kanji": list(s.applies_to_kanji),
            "applies_to_kana": list(s.applies_to_kana),
            "gloss": [asdict(g) for g in s.gloss],
            "xref": list(s.xref),
        }
        for s in e.senses
    ]
    return (
        e.seq,
        headword,
        e.readings[0].text,
        is_common,
        _json([_form_json(f, reading=False) for f in e.kanji]),
        _json([_form_json(f, reading=True) for f in e.readings]),
        _json(senses),
        _union(s.pos for s in e.senses),
        _union(s.misc for s in e.senses),
    )


def form_rows(e: Entry) -> list[tuple[Any, ...]]:
    rows: list[tuple[Any, ...]] = []
    for kind, forms in (("kanji", e.kanji), ("reading", e.readings)):
        for i, f in enumerate(forms):
            rows.append((e.seq, kind, i, f.text, normalize_form(f.text), f.common))
    return rows


def gloss_rows(e: Entry) -> list[tuple[Any, ...]]:
    return [
        (e.seq, si, gi, g.lang, g.text)
        for si, s in enumerate(e.senses)
        for gi, g in enumerate(s.gloss)
    ]


def entry_kanji_rows(e: Entry) -> list[tuple[Any, ...]]:
    chars = dict.fromkeys(ch for f in e.kanji for ch in f.text if is_kanji(ch))
    return [(e.seq, ch) for ch in chars]


_STAGING = """
CREATE TEMP TABLE stg_entry (
  seq bigint PRIMARY KEY, headword text, headword_reading text, is_common boolean,
  kanji_forms jsonb, readings jsonb, senses jsonb, pos text[], misc text[]
) ON COMMIT DROP;
CREATE TEMP TABLE stg_form (
  seq bigint, kind text, ord smallint, form text, norm text, common boolean
) ON COMMIT DROP;
CREATE TEMP TABLE stg_gloss (
  seq bigint, sense_ord smallint, gloss_ord smallint, lang text, gloss text
) ON COMMIT DROP;
CREATE TEMP TABLE stg_ek (seq bigint, literal text) ON COMMIT DROP;
CREATE TEMP TABLE stg_kanji (
  literal text PRIMARY KEY, on_readings text[], kun_readings text[], nanori text[],
  meanings text[], stroke_count smallint, grade smallint, jlpt smallint, freq_rank integer,
  radical text, components text[]
) ON COMMIT DROP;
"""


def _copy(cur: psycopg.Cursor[Any], table: str, rows: Iterable[tuple[Any, ...]]) -> int:
    n = 0
    with cur.copy(f"COPY {table} FROM STDIN") as cp:
        for r in rows:
            cp.write_row(r)
            n += 1
    return n


def _kanji_row(k: Kanji) -> tuple[Any, ...]:
    return (
        k.literal,
        list(k.on_readings),
        list(k.kun_readings),
        list(k.nanori),
        list(k.meanings),
        k.stroke_count,
        k.grade,
        k.jlpt,
        k.freq_rank,
        k.radical,
        list(k.components),
    )


def load_release(
    conn: psycopg.Connection[Any],
    release: ReleaseInfo,
    entries: Iterable[Entry],
    kanji: Iterable[Kanji],
) -> LoadResult:
    """Load a full JMdict + KANJIDIC2 snapshot as ``release``. Atomic and idempotent.

    ``entries`` must be the complete JMdict: anything absent is retired.
    """
    with conn.transaction(), conn.cursor() as cur:
        cur.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
        cur.execute("SELECT max(id) FROM ref.dict_release")
        row = cur.fetchone()
        newest = row[0] if row else None
        if newest is not None and release.id < newest:
            raise ValueError(f"release {release.id} is older than loaded release {newest}")
        cur.execute("SELECT version FROM ref.dict_release WHERE id = %s", (release.id,))
        existing = cur.fetchone()
        if existing is not None and existing[0] != release.version:
            raise ValueError(
                f"release {release.id} already loaded as version {existing[0]!r}, "
                f"not {release.version!r}"
            )
        cur.execute(
            """
            INSERT INTO ref.dict_release (id, version, built_at, sources, artifacts)
            VALUES (%s, %s, %s, %s::jsonb, %s::jsonb)
            ON CONFLICT (id) DO UPDATE
              SET built_at = EXCLUDED.built_at, sources = EXCLUDED.sources,
                  artifacts = EXCLUDED.artifacts
            """,
            (
                release.id,
                release.version,
                datetime.fromisoformat(release.built_at),
                _json(release.sources),
                _json(release.artifacts),
            ),
        )

        cur.execute(_STAGING)

        n_entries = 0
        for batch in batched(entries, 5000):
            _copy(cur, "stg_entry", (entry_row(e) for e in batch))
            _copy(cur, "stg_form", (r for e in batch for r in form_rows(e)))
            _copy(cur, "stg_gloss", (r for e in batch for r in gloss_rows(e)))
            _copy(cur, "stg_ek", (r for e in batch for r in entry_kanji_rows(e)))
            n_entries += len(batch)
        n_kanji = _copy(cur, "stg_kanji", (_kanji_row(k) for k in kanji))

        cur.execute(
            """SELECT count(*) FROM ref.dict_entry d JOIN stg_entry s USING (seq)
               WHERE d.retired_release_id IS NOT NULL"""
        )
        reappeared = _scalar(cur)

        cur.execute(
            """
            INSERT INTO ref.dict_entry (seq, headword, headword_reading, is_common, kanji_forms,
                                        readings, senses, pos, misc, added_release_id)
            SELECT seq, headword, headword_reading, is_common, kanji_forms, readings, senses,
                   pos, misc, %s FROM stg_entry
            ON CONFLICT (seq) DO UPDATE SET
              headword = EXCLUDED.headword, headword_reading = EXCLUDED.headword_reading,
              is_common = EXCLUDED.is_common, kanji_forms = EXCLUDED.kanji_forms,
              readings = EXCLUDED.readings, senses = EXCLUDED.senses, pos = EXCLUDED.pos,
              misc = EXCLUDED.misc, retired_release_id = NULL
            """,
            (release.id,),
        )

        cur.execute(
            """
            WITH gone AS (
              UPDATE ref.dict_entry d SET retired_release_id = %s
              WHERE d.retired_release_id IS NULL
                AND NOT EXISTS (SELECT 1 FROM stg_entry s WHERE s.seq = d.seq)
              RETURNING d.seq
            ), f AS (DELETE FROM ref.dict_entry_form WHERE seq IN (SELECT seq FROM gone)),
               g AS (DELETE FROM ref.dict_entry_gloss WHERE seq IN (SELECT seq FROM gone))
            SELECT count(*) FROM gone
            """,
            (release.id,),
        )
        retired = _scalar(cur)

        # Live entries: rebuild their derived rows from the staged data.
        cur.execute("DELETE FROM ref.dict_entry_form WHERE seq IN (SELECT seq FROM stg_entry)")
        cur.execute("DELETE FROM ref.dict_entry_gloss WHERE seq IN (SELECT seq FROM stg_entry)")
        cur.execute("DELETE FROM ref.entry_kanji WHERE seq IN (SELECT seq FROM stg_entry)")
        cur.execute("INSERT INTO ref.dict_entry_form SELECT * FROM stg_form")
        cur.execute("INSERT INTO ref.dict_entry_gloss SELECT * FROM stg_gloss")
        cur.execute("INSERT INTO ref.entry_kanji SELECT * FROM stg_ek")

        cur.execute(
            """
            INSERT INTO ref.kanji (literal, on_readings, kun_readings, nanori, meanings,
                                   stroke_count, grade, jlpt, freq_rank, radical, components)
            SELECT * FROM stg_kanji
            ON CONFLICT (literal) DO UPDATE SET
              on_readings = EXCLUDED.on_readings, kun_readings = EXCLUDED.kun_readings,
              nanori = EXCLUDED.nanori, meanings = EXCLUDED.meanings,
              stroke_count = EXCLUDED.stroke_count, grade = EXCLUDED.grade,
              jlpt = EXCLUDED.jlpt, freq_rank = EXCLUDED.freq_rank,
              radical = EXCLUDED.radical, components = EXCLUDED.components
            """
        )

        cur.execute(
            "UPDATE ref.dict_release SET is_current = false WHERE is_current AND id <> %s",
            (release.id,),
        )
        cur.execute("UPDATE ref.dict_release SET is_current = true WHERE id = %s", (release.id,))
        cur.execute("SELECT count(*) FROM ref.dict_entry WHERE retired_release_id IS NULL")
        live = _scalar(cur)
    return LoadResult(release.id, live, retired, reappeared, n_kanji)


def _scalar(cur: psycopg.Cursor[Any]) -> int:
    row = cur.fetchone()
    return int(row[0]) if row else 0
