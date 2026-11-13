"""ASS/SSA extractor: Dialogue lines only."""

from __future__ import annotations

import re
from collections.abc import Mapping

from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence
from kotobaya_worker.extract.subs.common import (
    cue_sentences,
    decode_text,
    episode,
    join_lines,
    strip_markup,
)

_STAMP = re.compile(r"(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})")
_DRAWING = re.compile(r"\{[^}]*\\p[1-9][^}]*\}")
_DEFAULT_FORMAT = ["Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV",
                   "Effect", "Text"]  # fmt: skip


def _ms(stamp: str) -> int | None:
    m = _STAMP.fullmatch(stamp.strip())
    if not m:
        return None
    h, mi, s, frac = m.groups()
    # ASS uses centiseconds ("0:00:01.50"); pad/truncate to ms.
    return ((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(frac.ljust(3, "0")[:3])


def extract_ass(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    text = decode_text(data).replace("\r\n", "\n").replace("\r", "\n")
    fields = _DEFAULT_FORMAT
    in_events = False
    cues: list[tuple[int, int, str]] = []
    for raw in text.split("\n"):
        line = raw.strip()
        if line.startswith("["):
            in_events = line.lower() == "[events]"
            continue
        if not in_events:
            continue
        key, _, value = line.partition(":")
        key = key.strip().lower()
        if key == "format":
            fields = [f.strip() for f in value.split(",")]
        elif key == "dialogue":  # "Comment" lines are ignored
            parts = value.lstrip().split(",", len(fields) - 1)
            if len(parts) < len(fields):
                continue
            row = dict(zip(fields, parts, strict=True))
            start, end = _ms(row.get("Start", "")), _ms(row.get("End", ""))
            body = row.get("Text", "")
            if start is None or end is None or _DRAWING.search(body):
                continue
            body = body.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            cues.append((start, end, join_lines([strip_markup(ln) for ln in body.split("\n")])))
    cues.sort(key=lambda c: (c[0], c[1]))  # stable; ASS files are often out of order
    sentences: list[ExtractedSentence] = []
    for start, end, body in cues:
        sentences.extend(cue_sentences(body, start, end))
    return episode(filename, sentences)
