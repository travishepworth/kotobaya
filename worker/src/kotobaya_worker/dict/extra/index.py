"""In-memory lookup of the loaded dictionary, keyed by T101's ``normalize_form``."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import psycopg

from ..normalize import normalize_form


@dataclass
class EntryIndex:
    """Live entries only (retired entries have no form rows, so they never match)."""

    by_norm: dict[str, set[int]] = field(default_factory=dict)  # any form -> seqs
    kanji_norm: dict[str, set[int]] = field(default_factory=dict)  # kanji forms only
    kana_norm: dict[str, set[int]] = field(default_factory=dict)  # readings only
    readings: dict[int, dict[str, str]] = field(default_factory=dict)  # seq -> norm -> reading
    pos: dict[int, frozenset[str]] = field(default_factory=dict)

    def match(self, term: str, reading: str | None = None) -> list[int]:
        """Entries having ``term`` as a form and (when given) ``reading`` as a reading."""
        seqs = self.by_norm.get(normalize_form(term), set())
        if reading:
            rn = normalize_form(reading)
            seqs = {s for s in seqs if rn in self.readings.get(s, {})}
        return sorted(seqs)

    def reading_text(self, seq: int, reading: str) -> str | None:
        """The reading exactly as in ``dict_entry.readings`` (None if the entry lacks it)."""
        return self.readings.get(seq, {}).get(normalize_form(reading))


def load_index(conn: psycopg.Connection[Any]) -> EntryIndex:
    idx = EntryIndex()
    with conn.cursor() as cur:
        cur.execute("SELECT seq, kind, form, norm FROM ref.dict_entry_form ORDER BY seq, kind, ord")
        for seq, kind, form, norm in cur:
            idx.by_norm.setdefault(norm, set()).add(seq)
            target = idx.kanji_norm if kind == "kanji" else idx.kana_norm
            target.setdefault(norm, set()).add(seq)
            if kind == "reading":
                idx.readings.setdefault(seq, {}).setdefault(norm, form)
        cur.execute("SELECT seq, pos FROM ref.dict_entry WHERE retired_release_id IS NULL")
        for seq, pos in cur:
            idx.pos[seq] = frozenset(pos)
    return idx
