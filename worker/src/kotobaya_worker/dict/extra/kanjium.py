"""Kanjium ``accents.txt``: ``term<TAB>reading<TAB>accent[,accent...]`` per line."""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

KANJIUM_URL = (
    "https://raw.githubusercontent.com/mifunetoshiro/kanjium/master/data/source_files/accents.txt"
)


@dataclass(frozen=True)
class PitchRow:
    term: str
    reading: str
    positions: tuple[int, ...]


def parse_accents(path: Path) -> Iterator[PitchRow]:
    """Yield every row; malformed lines raise (nothing is silently skipped)."""
    with path.open(encoding="utf-8") as f:
        for n, raw in enumerate(f, 1):
            line = raw.rstrip("\r\n")
            if not line.strip():
                continue
            cols = line.split("\t")
            if len(cols) < 3:
                raise ValueError(f"{path.name}:{n}: expected 3 tab-separated columns")
            term, reading, accent = cols[0].strip(), cols[1].strip(), cols[2].strip()
            try:
                positions = tuple(int(a) for a in accent.split(",") if a.strip() != "")
            except ValueError as e:
                raise ValueError(f"{path.name}:{n}: bad accent {accent!r}") from e
            if not term or not positions or any(p < 0 for p in positions):
                raise ValueError(f"{path.name}:{n}: empty term or accent")
            yield PitchRow(term, reading or term, positions)
