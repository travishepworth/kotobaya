"""Yomitan frequency dictionaries: a zip of ``term_meta_bank_*.json`` (``[term, "freq", data]``)."""

from __future__ import annotations

import json
import re
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_BANK = re.compile(r"(^|/)term_meta_bank_(\d+)\.json$")


@dataclass(frozen=True)
class FreqRow:
    term: str
    reading: str | None
    rank: int


def _value(data: Any) -> tuple[str | None, int]:
    """Decode the ``freq`` payload: int, {value}, or {reading, frequency: int | {value}}."""
    reading: str | None = None
    if isinstance(data, dict) and "frequency" in data:
        reading = data.get("reading")
        data = data["frequency"]
    if isinstance(data, dict):
        data = data.get("value")
    if isinstance(data, bool) or not isinstance(data, int | float) or data < 1:
        raise ValueError(f"unusable frequency value: {data!r}")
    return reading, int(data)


def parse_freq_zip(path: Path) -> Iterator[FreqRow]:
    """Values are taken as ranks (1 = most frequent). Non-``freq`` meta rows (pitch, ipa) are
    skipped; a ``freq`` row with an unusable value raises."""
    with zipfile.ZipFile(path) as z:
        banks = sorted((int(m.group(2)), n) for n in z.namelist() if (m := _BANK.search(n)))
        if not banks:
            raise ValueError(f"{path.name}: no term_meta_bank_*.json")
        for _, name in banks:
            for row in json.loads(z.read(name).decode("utf-8")):
                if len(row) != 3 or row[1] != "freq":
                    continue
                reading, rank = _value(row[2])
                yield FreqRow(str(row[0]), reading, rank)
