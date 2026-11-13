"""WebVTT extractor."""

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

_STAMP = r"(?:(\d+):)?(\d{1,2}):(\d{1,2})\.(\d{1,3})"
_TIME = re.compile(rf"{_STAMP}\s*-->\s*{_STAMP}")


def _ms(h: str | None, m: str, s: str, frac: str) -> int:
    return ((int(h or 0) * 60 + int(m)) * 60 + int(s)) * 1000 + int(frac.ljust(3, "0"))


def extract_vtt(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    text = decode_text(data).replace("\r\n", "\n").replace("\r", "\n")
    sentences: list[ExtractedSentence] = []
    for block in re.split(r"\n\s*\n", text):
        lines = block.strip("\n").split("\n")
        if not lines or lines[0].startswith(("WEBVTT", "NOTE", "STYLE", "REGION")):
            continue
        idx = next((i for i, ln in enumerate(lines) if "-->" in ln), None)
        if idx is None:
            continue
        m = _TIME.match(lines[idx].strip())  # anything after the end stamp is cue settings
        if not m:
            continue
        start = _ms(*m.group(1, 2, 3, 4))
        end = _ms(*m.group(5, 6, 7, 8))
        body = join_lines([strip_markup(ln) for ln in lines[idx + 1 :]])
        sentences.extend(cue_sentences(body, start, end))
    return episode(filename, sentences)
