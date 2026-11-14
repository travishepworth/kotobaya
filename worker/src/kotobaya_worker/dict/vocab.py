"""Vocabulary lists (JLPT) loaded inside the dict release transaction (contracts/db/VOCAB_LISTS.md 2-3).

``parse_vocab_csv`` reads one source file; ``load_vocab_lists`` matches the rows against the live entries
of the release being loaded and replaces ``ref.vocab_list`` / ``vocab_list_item`` / ``vocab_list_unmatched``.
"""

from __future__ import annotations

import csv
import hashlib
import logging
import re
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg

from kotobaya_worker.logs import job_summary

from .fetch import SourceSpec
from .models import VocabListReport

log = logging.getLogger("kotobaya.worker.vocab")

JLPT_LEVELS = ("n5", "n4", "n3", "n2", "n1")
JLPT_FAMILY = "jlpt"
JLPT_COMMIT = "b062d4e38c4bdd0950ae1d4ec55f04b176182e03"
_JLPT_BASE = (
    f"https://raw.githubusercontent.com/stephenmk/yomitan-jlpt-vocab/{JLPT_COMMIT}/original_data/"
)
JLPT_DEFAULT: dict[str, tuple[str, str]] = {  # level -> (url, sha256), VOCAB_LISTS.md 2.1
    "n5": (
        _JLPT_BASE + "n5.csv",
        "07dc6f197b51cc076c65c3c9f23218d10c2b67f76545f5c1c4d9e3750495533a",
    ),
    "n4": (
        _JLPT_BASE + "n4.csv",
        "a14dcc7fdc02259b22331a486c6ff66df74c16472c8aeb5a0ebe7e5fa8ee8eb4",
    ),
    "n3": (
        _JLPT_BASE + "n3.csv",
        "bd4d68c59cfee861351e1bdf9d99818e434392201eabbed4d3657225fe53a3ac",
    ),
    "n2": (
        _JLPT_BASE + "n2.csv",
        "42a4413326e857d701dc9659b2711637ab612304a339fe0d09f4f0f042d3a214",
    ),
    "n1": (
        _JLPT_BASE + "n1.csv",
        "7a58f0584e9ec2b0299ccb9b109f3bd03e08b90d129a714307c0a1cb72f07b32",
    ),
}
JLPT_LICENSE = "CC BY-SA 4.0 (list data: Jonathan Waller, tanos.co.uk, CC BY)"
JLPT_PROJECT_URL = "https://github.com/stephenmk/yomitan-jlpt-vocab"

_SEQ = re.compile(r"[1-9][0-9]{0,15}")
_WS = " \t\r\n\f\v"


def jlpt_default_specs() -> dict[str, SourceSpec]:
    return {
        lvl: SourceSpec(f"jlpt-{lvl}.csv", url, sha) for lvl, (url, sha) in JLPT_DEFAULT.items()
    }


def jlpt_fingerprint(specs: Mapping[str, SourceSpec]) -> str:
    """One digest over the five (resolved) file digests: the source fingerprint of the JLPT lists."""
    text = "\n".join(f"{lvl}:{specs[lvl].sha256 or ''}" for lvl in JLPT_LEVELS)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def jlpt_provenance(specs: Mapping[str, SourceSpec]) -> dict[str, str]:
    """``ref.dict_release.sources.jlpt`` (VOCAB_LISTS.md 2.1) plus the combined ``sha256`` fingerprint."""
    default = all(
        (specs[lvl].url, (specs[lvl].sha256 or "").lower()) == JLPT_DEFAULT[lvl]
        for lvl in JLPT_LEVELS
    )
    return {
        "version": JLPT_COMMIT if default else (specs["n5"].sha256 or ""),
        "license": JLPT_LICENSE,
        "url": JLPT_PROJECT_URL,
        "sha256": jlpt_fingerprint(specs),
    }


@dataclass(frozen=True)
class VocabRow:
    source_row: int  # 1-based data-row number
    seq_text: str
    kana: str
    kanji: str
    cells: int  # number of cells in the line (fewer than the header's = invalid)
    width: int  # the header's column count

    @property
    def jmdict_seq(self) -> int | None:
        return int(self.seq_text) if _SEQ.fullmatch(self.seq_text) else None

    @property
    def invalid(self) -> bool:
        return (
            self.cells < self.width
            or not self.kana
            or (self.seq_text != "" and not _SEQ.fullmatch(self.seq_text))
        )


@dataclass(frozen=True)
class VocabSource:
    id: str
    family: str
    ordinal: int
    title: str
    rows: Sequence[VocabRow]


def _clean(v: str) -> str:
    return unicodedata.normalize("NFC", v.strip(_WS))


def parse_vocab_csv(path: Path, label: str) -> list[VocabRow]:
    """RFC 4180 CSV, header with ``jmdict_seq``, ``kana``, ``kanji`` (VOCAB_LISTS.md 2.3)."""
    with path.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        names = [_clean(h) for h in header] if header else []
        try:
            i_seq, i_kana, i_kanji = (names.index(c) for c in ("jmdict_seq", "kana", "kanji"))
        except ValueError:
            raise ValueError(f"invalid_source: {label}") from None
        rows: list[VocabRow] = []
        for cells in reader:
            if not cells:  # blank line
                continue

            def cell(i: int, cells: list[str] = cells) -> str:
                return _clean(cells[i]) if i < len(cells) else ""

            rows.append(
                VocabRow(
                    len(rows) + 1, cell(i_seq), cell(i_kana), cell(i_kanji), len(cells), len(names)
                )
            )
    if not rows:  # header only: loading it would silently empty the list (VOCAB_LISTS.md 3.3)
        raise ValueError(f"invalid_source: {label} has no data rows")
    return rows


def jlpt_sources(files: Mapping[str, Path]) -> list[VocabSource]:
    """The five lists in ordinal order, parsed from the fetched files."""
    return [
        VocabSource(
            f"jlpt-{lvl}",
            JLPT_FAMILY,
            ordinal,
            f"JLPT {lvl.upper()}",
            parse_vocab_csv(files[lvl], f"jlpt {lvl}"),
        )
        for ordinal, lvl in enumerate(JLPT_LEVELS)
    ]


def load_vocab_lists(
    cur: psycopg.Cursor[Any], release_id: int, sources: Sequence[VocabSource]
) -> tuple[VocabListReport, ...]:
    """Replace the vocabulary tables (VOCAB_LISTS.md 3.4). Call inside the transaction that makes
    ``release_id`` current, after its ``dict_entry`` rows are final. Raises if an invariant fails."""
    cur.execute("DELETE FROM ref.vocab_list")
    all_rows = [r for s in sources for r in s.rows if not r.invalid]
    seqs = sorted({r.jmdict_seq for r in all_rows if r.jmdict_seq is not None})
    kanas = sorted({r.kana for r in all_rows})
    kanjis = sorted({r.kanji for r in all_rows if r.kanji})

    cur.execute(
        "SELECT seq FROM ref.dict_entry WHERE retired_release_id IS NULL AND seq = ANY(%s)", (seqs,)
    )
    live_seqs = {int(r[0]) for r in cur.fetchall()}

    def forms(kind: str, wanted: list[str]) -> dict[str, set[int]]:
        cur.execute(
            "SELECT f.form, f.seq FROM ref.dict_entry_form f JOIN ref.dict_entry d USING (seq) "
            "WHERE d.retired_release_id IS NULL AND f.kind = %s AND f.form = ANY(%s)",
            (kind, wanted),
        )
        out: dict[str, set[int]] = {}
        for form, seq in cur.fetchall():
            out.setdefault(form, set()).add(int(seq))
        return out

    readings = forms("reading", kanas)
    kanji_forms = forms("kanji", kanjis)

    def kana_only(candidates: set[int]) -> set[int]:
        cur.execute(
            "SELECT seq FROM ref.dict_entry WHERE seq = ANY(%s) AND jsonb_array_length(kanji_forms) = 0",
            (sorted(candidates),),
        )
        return {int(r[0]) for r in cur.fetchall()}

    def match(row: VocabRow) -> tuple[int | None, str, list[int]]:
        """(seq, '', []) on a match, else (None, reason, candidates)."""
        if row.invalid:
            return None, "invalid_row", []
        seq = row.jmdict_seq
        if seq is not None and seq in live_seqs:
            return seq, "", []
        cands = set(readings.get(row.kana, ()))
        if row.kanji:
            cands &= kanji_forms.get(row.kanji, set())
        if len(cands) > 1 and not row.kanji:
            cands = kana_only(cands)
        if len(cands) == 1:
            return next(iter(cands)), "", []
        if not cands:
            return None, "no_entry", []
        return None, "ambiguous", sorted(cands)

    family_seen: dict[str, set[int]] = {}
    reports: list[VocabListReport] = []
    for src in sorted(sources, key=lambda s: s.ordinal):
        seen_family = family_seen.setdefault(src.family, set())
        items: dict[int, int] = {}  # seq -> position
        unmatched: list[tuple[Any, ...]] = []
        duplicates = shadowed = 0
        for row in src.rows:
            seq, reason, cands = match(row)
            if seq is None:
                unmatched.append(
                    (src.id, row.source_row, row.jmdict_seq, row.kana, row.kanji, reason, cands)
                )
            elif seq in seen_family:
                shadowed += 1
            elif seq in items:
                duplicates += 1
            else:
                items[seq] = row.source_row
        n_rows = len(src.rows)
        if n_rows != len(items) + duplicates + shadowed + len(unmatched):
            raise ValueError(f"vocab list {src.id}: row accounting does not add up")
        cur.execute(
            "INSERT INTO ref.vocab_list (id, family, ordinal, title, release_id, entry_count, "
            "source_rows, duplicate_rows, shadowed_rows, unmatched_rows) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
            (src.id, src.family, src.ordinal, src.title, release_id, len(items), n_rows,
             duplicates, shadowed, len(unmatched)),
        )  # fmt: skip
        cur.executemany(
            "INSERT INTO ref.vocab_list_item (list_id, seq, position) VALUES (%s, %s, %s)",
            [(src.id, s, p) for s, p in items.items()],
        )
        cur.executemany(
            "INSERT INTO ref.vocab_list_unmatched "
            "(list_id, source_row, jmdict_seq, kana, kanji, reason, candidates) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s)",
            unmatched,
        )
        seen_family |= set(items)
        report = VocabListReport(src.id, n_rows, len(items), duplicates, shadowed, len(unmatched))
        reports.append(report)
        _log_line(report)
    _check_invariants(cur, reports)
    return tuple(reports)


def _log_line(r: VocabListReport) -> int:
    """Per-list summary: unmatched rows WARN; rows in but no entry out ERRORs (0.1.9 JLPT decks)."""
    return job_summary(
        log,
        f"vocab list {r.id}",
        inputs={"source_rows": r.source_rows},
        outputs={"entries": r.entries, "shadowed": r.shadowed},
        dropped={"unmatched": r.unmatched},
        list_id=r.id,
        duplicates=r.duplicates,
    )


def _check_invariants(cur: psycopg.Cursor[Any], reports: Sequence[VocabListReport]) -> None:
    """VOCAB_LISTS.md 3.3: stored counts equal the stored rows; no seq in two lists of one family."""
    cur.execute(
        "SELECT l.id, l.entry_count, l.unmatched_rows, "
        "(SELECT count(*) FROM ref.vocab_list_item i WHERE i.list_id = l.id), "
        "(SELECT count(*) FROM ref.vocab_list_unmatched u WHERE u.list_id = l.id) "
        "FROM ref.vocab_list l"
    )
    for lid, entry_count, unmatched_rows, n_items, n_unmatched in cur.fetchall():
        if (entry_count, unmatched_rows) != (n_items, n_unmatched):
            raise ValueError(f"vocab list {lid}: stored counts differ from stored rows")
    cur.execute(
        "SELECT i.seq FROM ref.vocab_list_item i JOIN ref.vocab_list l ON l.id = i.list_id "
        "GROUP BY l.family, i.seq HAVING count(*) > 1 LIMIT 1"
    )
    if cur.fetchone() is not None:
        raise ValueError("vocab lists: an entry belongs to two lists of one family")
    if len(reports) != len({r.id for r in reports}):
        raise ValueError("vocab lists: duplicate list id")
