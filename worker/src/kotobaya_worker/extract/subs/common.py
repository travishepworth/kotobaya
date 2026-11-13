"""Helpers shared by the subtitle extractors."""

from __future__ import annotations

import html
import re

from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.split import split_sentences

_RUBY_ANNOT = re.compile(r"<(rt|rp)\b[^>]*>.*?</\1\s*>", re.IGNORECASE | re.DOTALL)
_TAG = re.compile(r"<[^>]*>")
_ASS_OVERRIDE = re.compile(r"\{[^}]*\}")


def decode_text(data: bytes) -> str:
    """Subtitle files are UTF-8 (BOM tolerated); fall back to Shift-JIS, then lossy UTF-8."""
    for enc in ("utf-8-sig", "cp932"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def strip_markup(text: str) -> str:
    """Remove HTML-ish tags (ruby annotations with their content), ASS `{...}` and entities."""
    text = _RUBY_ANNOT.sub("", text)
    text = _TAG.sub("", text)
    text = _ASS_OVERRIDE.sub("", text)
    return html.unescape(text)


def join_lines(lines: list[str]) -> str:
    """Join the lines of one cue; Japanese joins directly, ASCII-on-ASCII gets a space."""
    out = ""
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if out and out[-1].isascii() and line[0].isascii():
            out += " "
        out += line
    return out


def cue_sentences(text: str, start_ms: int, end_ms: int) -> list[ExtractedSentence]:
    """One sentence per cue. Split rule: a cue that holds two or more sentences (a terminator
    followed by more text outside any bracket pair) becomes one sentence per piece, each keeping
    the cue's timestamps. Cues with a single sentence, or only a sound-effect cue such as
    （猫の鳴き声）, stay whole."""
    end_ms = max(end_ms, start_ms)
    return [ExtractedSentence(p, start_ms, end_ms) for p in split_sentences(text)]


def episode(filename: str, sentences: list[ExtractedSentence]) -> list[ExtractedSegment]:
    return [ExtractedSegment("episode", default_title(filename), tuple(sentences))]
