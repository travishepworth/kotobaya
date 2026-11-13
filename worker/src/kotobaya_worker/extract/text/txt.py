"""Plain-text extractor (books)."""

from __future__ import annotations

import re
from collections.abc import Mapping

from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.split import CLOSERS, TERMINATORS, split_sentences

_HEADING = re.compile(
    r"^\s*(?:第\s*[0-9０-９〇一二三四五六七八九十百千]+\s*章.*|Chapter\s+\d+\b.*)$",
    re.IGNORECASE,
)
_MAX_HEADING = 40


def decode_japanese(data: bytes) -> str:
    """Sniff utf-8 / utf-8-sig / shift_jis / euc-jp. Between the legacy encodings (both may
    decode the same bytes) the one yielding the larger share of kana/kanji wins."""
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        pass
    best: tuple[float, str] | None = None
    for enc in ("cp932", "euc_jp"):
        try:
            s = data.decode(enc)
        except UnicodeDecodeError:
            continue
        jp = sum(1 for c in s if "぀" <= c <= "ヿ" or "一" <= c <= "鿿")
        score = jp / max(len(s), 1)
        if best is None or score > best[0]:
            best = (score, s)
    if best is not None:
        return best[1]
    return data.decode("utf-8", errors="replace")


def _is_heading(line: str) -> bool:
    return len(line.strip()) <= _MAX_HEADING and _HEADING.match(line) is not None


def _paragraph_sentences(lines: list[str]) -> list[str]:
    """Hard-wrapped lines are joined; a line that ends in a terminator or closing bracket
    starts a new sentence, so one-line-per-paragraph prose and dialogue stay separate."""
    text = ""
    for line in lines:
        if text:
            text += "\n" if text[-1] in TERMINATORS or text[-1] in CLOSERS else ""
        text += line.strip()
    return split_sentences(text)


def extract_txt(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    text = decode_japanese(data).replace("\r\n", "\n").replace("\r", "\n")
    title = default_title(filename)
    chapters: list[tuple[str | None, list[str]]] = [(None, [])]  # (heading, raw lines)
    for line in text.split("\n"):
        if _is_heading(line):
            chapters.append((line.strip(), []))
        else:
            chapters[-1][1].append(line)
    has_headings = len(chapters) > 1

    segments: list[ExtractedSegment] = []
    for heading, lines in chapters:
        sentences: list[str] = []
        para: list[str] = []
        for line in [*lines, ""]:
            if line.strip():
                para.append(line)
            elif para:
                sentences.extend(_paragraph_sentences(para))
                para = []
        if not sentences:
            continue
        items = tuple(ExtractedSentence(s) for s in sentences)
        if has_headings:
            segments.append(ExtractedSegment("chapter", heading or title, items))
        else:
            segments.append(ExtractedSegment("whole", title, items))
    return segments
