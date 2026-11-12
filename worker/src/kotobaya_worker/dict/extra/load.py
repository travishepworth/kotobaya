"""Load pitch, frequency and example data into ``ref.*`` (contracts/db/SCHEMA.md 4.5).

Every loader replaces its own tables wholesale (frequency: per list), so a re-run with the same
inputs leaves identical rows. Callers run them in one transaction after T101's release load.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

import psycopg

from .freq import FreqRow
from .index import EntryIndex
from .kanjium import PitchRow
from .tatoeba import LinkIndex, Pair, link_sentence

_LOCK_KEY = 0x4B4F5402  # distinct from T101's lock; serialises extra loads


@dataclass(frozen=True)
class PitchResult:
    rows: int  # input rows
    unmatched: int  # input rows with no entry/reading match
    loaded: int  # entry_pitch rows written


@dataclass(frozen=True)
class FreqResult:
    list_id: str
    rows: int
    unmatched: int
    loaded: int  # entry_freq rows written (one per entry)


@dataclass(frozen=True)
class ExampleResult:
    sentences: int
    unlinked: int  # stored, but matched no entry
    links: int


@dataclass(frozen=True)
class FreqListConfig:
    id: str
    title: str
    priority: int
    source: str | None = None


@dataclass(frozen=True)
class CoverageReport:
    entries: int
    with_pitch: int
    with_freq_rank: int
    with_examples: int

    def render(self) -> str:
        def pct(n: int) -> str:
            return f"{n} ({100 * n / self.entries:.1f}%)" if self.entries else f"{n}"

        return (
            f"live entries:      {self.entries}\n"
            f"  with pitch:      {pct(self.with_pitch)}\n"
            f"  with freq rank:  {pct(self.with_freq_rank)}\n"
            f"  with examples:   {pct(self.with_examples)}"
        )


def _copy(cur: psycopg.Cursor[Any], table: str, rows: Iterable[tuple[Any, ...]]) -> int:
    n = 0
    with cur.copy(f"COPY {table} FROM STDIN") as cp:
        for r in rows:
            cp.write_row(r)
            n += 1
    return n


def begin(conn: psycopg.Connection[Any], release_id: int) -> None:
    """Take the lock and require that T101 has loaded ``release_id``."""
    conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_KEY,))
    row = conn.execute("SELECT 1 FROM ref.dict_release WHERE id = %s", (release_id,)).fetchone()
    if row is None:
        raise ValueError(f"release {release_id} is not loaded; run the JMdict load first")


def record_source(
    conn: psycopg.Connection[Any], release_id: int, key: str, info: dict[str, str]
) -> None:
    conn.execute(
        "UPDATE ref.dict_release SET sources = sources || jsonb_build_object(%s::text, %s::jsonb)"
        " WHERE id = %s",
        (key, json.dumps(info, ensure_ascii=False, sort_keys=True), release_id),
    )


def load_pitch(
    conn: psycopg.Connection[Any], idx: EntryIndex, rows: Iterable[PitchRow]
) -> PitchResult:
    merged: dict[tuple[int, str], dict[int, None]] = {}
    total = unmatched = 0
    for r in rows:
        total += 1
        hit = False
        for seq in idx.match(r.term, r.reading):
            text = idx.reading_text(seq, r.reading)
            if text is None:
                continue
            hit = True
            slot = merged.setdefault((seq, text), {})
            for p in r.positions:
                slot.setdefault(p, None)
        if not hit:
            unmatched += 1
    with conn.cursor() as cur:
        cur.execute("DELETE FROM ref.entry_pitch")
        loaded = _copy(
            cur,
            "ref.entry_pitch (seq, reading, positions)",
            ((seq, reading, list(pos)) for (seq, reading), pos in sorted(merged.items())),
        )
    return PitchResult(total, unmatched, loaded)


def load_freq_list(
    conn: psycopg.Connection[Any],
    idx: EntryIndex,
    cfg: FreqListConfig,
    rows: Iterable[FreqRow],
) -> FreqResult:
    best: dict[int, int] = {}
    total = unmatched = 0
    for r in rows:
        total += 1
        seqs = idx.match(r.term, r.reading)
        if not seqs:
            unmatched += 1
        for seq in seqs:
            if seq not in best or r.rank < best[seq]:
                best[seq] = r.rank
    with conn.cursor() as cur:
        cur.execute("DELETE FROM ref.entry_freq WHERE list_id = %s", (cfg.id,))
        cur.execute(
            """
            INSERT INTO ref.freq_list (id, title, priority, entry_count, source)
            VALUES (%s, %s, %s, 0, %s)
            ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, source = EXCLUDED.source
            """,
            (cfg.id, cfg.title, cfg.priority, cfg.source),
        )
        # Priority is UNIQUE; moving it to a free slot is allowed, a clash raises.
        cur.execute("UPDATE ref.freq_list SET priority = %s WHERE id = %s", (cfg.priority, cfg.id))
        loaded = _copy(
            cur,
            "ref.entry_freq (seq, list_id, rank)",
            ((s, cfg.id, r) for s, r in sorted(best.items())),
        )
        cur.execute("UPDATE ref.freq_list SET entry_count = %s WHERE id = %s", (loaded, cfg.id))
    return FreqResult(cfg.id, total, unmatched, loaded)


def recompute_freq_rank(conn: psycopg.Connection[Any]) -> int:
    """``dict_entry.freq_rank`` = rank in the lowest-priority-number list containing the entry."""
    cur = conn.execute(
        """
        WITH best AS (
          SELECT DISTINCT ON (ef.seq) ef.seq, ef.rank
          FROM ref.entry_freq ef JOIN ref.freq_list fl ON fl.id = ef.list_id
          ORDER BY ef.seq, fl.priority
        )
        UPDATE ref.dict_entry d SET freq_rank = b.rank
        FROM (SELECT d2.seq, best.rank FROM ref.dict_entry d2
              LEFT JOIN best ON best.seq = d2.seq) b
        WHERE d.seq = b.seq AND d.freq_rank IS DISTINCT FROM b.rank
        """
    )
    return cur.rowcount


def load_examples(
    conn: psycopg.Connection[Any], links: LinkIndex, pairs: Iterable[Pair]
) -> ExampleResult:
    sentences: list[tuple[Any, ...]] = []
    edges: list[tuple[int, int]] = []
    unlinked = 0
    for p in pairs:
        seqs = link_sentence(p.text, links)
        sentences.append((p.id, "ja", p.text, p.translation, p.translation_id))
        if not seqs:
            unlinked += 1
        edges.extend((p.id, s) for s in sorted(seqs))
    with conn.cursor() as cur:
        cur.execute("DELETE FROM ref.example_sentence")  # cascades to the link table
        _copy(
            cur,
            "ref.example_sentence (id, language, text, translation, translation_id)",
            sentences,
        )
        _copy(cur, "ref.example_sentence_entry (sentence_id, seq)", edges)
    return ExampleResult(len(sentences), unlinked, len(edges))


def coverage(conn: psycopg.Connection[Any]) -> CoverageReport:
    row = conn.execute(
        """
        SELECT count(*),
               count(*) FILTER (WHERE EXISTS (SELECT 1 FROM ref.entry_pitch p WHERE p.seq = d.seq)),
               count(*) FILTER (WHERE d.freq_rank IS NOT NULL),
               count(*) FILTER (WHERE EXISTS
                 (SELECT 1 FROM ref.example_sentence_entry e WHERE e.seq = d.seq))
        FROM ref.dict_entry d WHERE d.retired_release_id IS NULL
        """
    ).fetchone()
    assert row is not None
    return CoverageReport(int(row[0]), int(row[1]), int(row[2]), int(row[3]))
