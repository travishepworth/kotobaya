"""Own-file import extractors (bytes -> segments of sentences)."""

from __future__ import annotations

from collections.abc import Mapping

from kotobaya_worker.extract.epub import extract_epub
from kotobaya_worker.extract.html import extract_html, extract_url
from kotobaya_worker.extract.model import (
    ExtractedSegment,
    ExtractedSentence,
    Extractor,
    ReadingHint,
    detect_format,
)
from kotobaya_worker.extract.mokuro import extract_mokuro
from kotobaya_worker.extract.subs import extract_ass, extract_srt, extract_vtt
from kotobaya_worker.extract.text import extract_texthooker, extract_txt

EXTRACTORS: dict[str, Extractor] = {
    "srt": extract_srt,
    "ass": extract_ass,
    "vtt": extract_vtt,
    "txt": extract_txt,
    "texthooker": extract_texthooker,
    "epub": extract_epub,
    "mokuro": extract_mokuro,
    "html": extract_html,
}


def extract(
    data: bytes, filename: str, fmt: str = "auto", options: Mapping[str, object] | None = None
) -> list[ExtractedSegment]:
    resolved = detect_format(filename, fmt)
    try:
        extractor = EXTRACTORS[resolved]
    except KeyError:
        raise ValueError(f"no extractor for format {resolved!r}") from None
    return extractor(data, filename, options or {})


FORMATS_IMPLEMENTED = tuple(EXTRACTORS)

__all__ = [
    "EXTRACTORS",
    "FORMATS_IMPLEMENTED",
    "ExtractedSegment",
    "ExtractedSentence",
    "Extractor",
    "ReadingHint",
    "detect_format",
    "extract",
    "extract_url",
]
