"""KANJIDIC2 and KRADFILE parsers."""

from __future__ import annotations

import gzip
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import replace
from pathlib import Path
from typing import BinaryIO, cast

from .models import Kanji

# KANJIDIC2 stores the old 4-level JLPT; ref.kanji.jlpt uses N-numbers (SCHEMA: 4->5, 3->4, 2->2, 1->1).
_JLPT_OLD_TO_N = {1: 1, 2: 2, 3: 4, 4: 5}


def _int(elem: ET.Element, path: str) -> int | None:
    e = elem.find(path)
    return int(e.text) if e is not None and e.text else None


def _character(c: ET.Element) -> Kanji:
    jlpt = _int(c, "misc/jlpt")
    radical = None
    for rv in c.findall("radical/rad_value"):
        if rv.get("rad_type") == "classical":
            radical = rv.text
    return Kanji(
        literal=(c.findtext("literal") or "").strip(),
        on_readings=tuple(
            r.text or "" for r in c.findall("reading_meaning/rmgroup/reading[@r_type='ja_on']")
        ),
        kun_readings=tuple(
            r.text or "" for r in c.findall("reading_meaning/rmgroup/reading[@r_type='ja_kun']")
        ),
        nanori=tuple(n.text or "" for n in c.findall("reading_meaning/nanori")),
        meanings=tuple(
            m.text or ""
            for m in c.findall("reading_meaning/rmgroup/meaning")
            if m.get("m_lang") in (None, "en")
        ),
        stroke_count=_int(c, "misc/stroke_count"),
        grade=_int(c, "misc/grade"),
        jlpt=_JLPT_OLD_TO_N.get(jlpt) if jlpt is not None else None,
        freq_rank=_int(c, "misc/freq"),
        radical=radical,
    )


def parse_kanjidic(path: Path) -> Iterator[Kanji]:
    """Stream characters from ``kanjidic2.xml`` (plain or ``.gz``)."""
    if path.suffix == ".gz":
        with gzip.open(path, "rb") as gz:
            yield from _iter_characters(cast(BinaryIO, gz))
    else:
        with path.open("rb") as f:
            yield from _iter_characters(f)


def _iter_characters(stream: BinaryIO) -> Iterator[Kanji]:
    for _, elem in ET.iterparse(stream, events=("end",)):
        if elem.tag == "character":
            yield _character(elem)
            elem.clear()


def parse_krad(data: bytes) -> dict[str, tuple[str, ...]]:
    """Parse KRADFILE / kradfile2 (EUC-JP): ``亜 : ｜ 一 口``. Comment lines start with ``#``."""
    out: dict[str, tuple[str, ...]] = {}
    for line in data.decode("euc-jp").splitlines():
        if not line or line.startswith("#"):
            continue
        head, sep, rest = line.partition(" : ")
        if not sep or len(head.strip()) != 1:
            raise ValueError(f"malformed KRADFILE line: {line!r}")
        out[head.strip()] = tuple(rest.split())
    return out


def merge_krad(*tables: Mapping[str, tuple[str, ...]]) -> dict[str, tuple[str, ...]]:
    """Merge KRADFILE tables; later tables do not override earlier entries."""
    merged: dict[str, tuple[str, ...]] = {}
    for table in tables:
        for k, v in table.items():
            merged.setdefault(k, v)
    return merged


def with_components(kanji: Iterable[Kanji], krad: Mapping[str, tuple[str, ...]]) -> Iterator[Kanji]:
    for k in kanji:
        yield replace(k, components=krad.get(k.literal, ()))
