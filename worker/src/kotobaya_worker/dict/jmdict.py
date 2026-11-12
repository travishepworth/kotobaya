"""Streaming JMdict_e parser."""

from __future__ import annotations

import gzip
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import Any, cast

from .models import Entry, Form, Gloss, Sense

_XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
_STD_ENTITIES = {"amp", "lt", "gt", "quot", "apos"}
_ENT_OPEN = ""
_ENT_CLOSE = ""
COMMON_PRI = frozenset({"news1", "ichi1", "spec1", "spec2", "gai1"})


def _protect_entities(line: str) -> str:
    """Replace ``&v1;``-style JMdict entities by private-use markers so the tag code survives.

    Expat would otherwise expand them to their long descriptions. JMdict puts one element
    per line and entities never span lines.
    """
    if "&" not in line:
        return line
    out: list[str] = []
    i = 0
    while True:
        j = line.find("&", i)
        if j < 0:
            out.append(line[i:])
            break
        k = line.find(";", j)
        name = line[j + 1 : k] if k > 0 else ""
        if k > 0 and name and not name.startswith("#") and name not in _STD_ENTITIES:
            out.append(line[i:j])
            out.append(f"{_ENT_OPEN}{name}{_ENT_CLOSE}")
            i = k + 1
        else:
            out.append(line[i : j + 1])
            i = j + 1
    return "".join(out)


def _lines(stream: Iterable[bytes]) -> Iterator[str]:
    in_dtd = False
    for raw in stream:
        line = raw.decode("utf-8")
        if in_dtd:
            if line.lstrip().startswith("]>"):
                in_dtd = False
            continue
        if line.lstrip().startswith("<!DOCTYPE") and "[" in line and "]>" not in line:
            in_dtd = True
            continue
        yield _protect_entities(line)


def _tag(text: str | None) -> str:
    t = (text or "").strip()
    if t.startswith(_ENT_OPEN) and t.endswith(_ENT_CLOSE):
        return t[1:-1]
    return t


def _tags(elem: ET.Element, name: str) -> tuple[str, ...]:
    return tuple(_tag(e.text) for e in elem.findall(name))


def _texts(elem: ET.Element, name: str) -> tuple[str, ...]:
    return tuple(e.text or "" for e in elem.findall(name))


def _text(elem: ET.Element, name: str) -> str:
    e = elem.find(name)
    return (e.text or "") if e is not None else ""


def _is_common(elem: ET.Element, pri: str) -> bool:
    return any(_tag(p.text) in COMMON_PRI for p in elem.findall(pri))


def _entry(e: ET.Element) -> Entry:
    kanji = tuple(
        Form(_text(k, "keb"), _is_common(k, "ke_pri"), _tags(k, "ke_inf")) for k in e.iter("k_ele")
    )
    readings = tuple(
        Form(
            _text(r, "reb"),
            _is_common(r, "re_pri"),
            _tags(r, "re_inf"),
            _texts(r, "re_restr") or ("*",),
        )
        for r in e.iter("r_ele")
    )
    senses: list[Sense] = []
    pos: tuple[str, ...] = ()
    for s in e.iter("sense"):
        own_pos = _tags(s, "pos")
        if own_pos:  # JMdict: pos is inherited from the previous sense when omitted
            pos = own_pos
        senses.append(
            Sense(
                pos=pos,
                misc=_tags(s, "misc"),
                field=_tags(s, "field"),
                dialect=_tags(s, "dial"),
                info=_texts(s, "s_inf"),
                applies_to_kanji=_texts(s, "stagk") or ("*",),
                applies_to_kana=_texts(s, "stagr") or ("*",),
                gloss=tuple(
                    Gloss(g.get(_XML_LANG, "eng"), g.text or "") for g in s.findall("gloss")
                ),
                xref=_texts(s, "xref"),
            )
        )
    return Entry(int(_text(e, "ent_seq")), kanji, readings, tuple(senses))


def parse_lines(lines: Iterable[str]) -> Iterator[Entry]:
    parser: ET.XMLPullParser[Any] = ET.XMLPullParser(events=("start", "end"))
    root: ET.Element | None = None
    for line in lines:
        parser.feed(line)
        for event, node in cast(Iterator[tuple[str, ET.Element]], parser.read_events()):
            elem = node
            if event == "start":
                if root is None:
                    root = elem
            elif elem.tag == "entry":
                yield _entry(elem)
                if root is not None:
                    root.clear()  # drop finished entries so memory stays flat
    parser.close()


def parse_jmdict(path: Path) -> Iterator[Entry]:
    """Stream entries from ``JMdict_e`` (plain or ``.gz``)."""
    if path.suffix == ".gz":
        with gzip.open(path, "rb") as gz:
            yield from parse_lines(_lines(gz))
    else:
        with path.open("rb") as f:
            yield from parse_lines(_lines(f))
