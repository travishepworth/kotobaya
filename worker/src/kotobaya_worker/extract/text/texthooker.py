"""Text-hooker log extractor: one hooked line per sentence."""

from __future__ import annotations

import re
from collections.abc import Mapping

from kotobaya_worker.extract.model import ExtractedSegment, ExtractedSentence, default_title
from kotobaya_worker.extract.text.txt import decode_japanese

_TIMESTAMP = re.compile(
    r"""^\s*(?:
        \[?\d{4}[-/]\d{2}[-/]\d{2}[ T]\d{1,2}:\d{2}(?::\d{2}(?:[.,]\d+)?)?\]?
      | \[?\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\]?
    )\s*[|\-:]?\s*""",
    re.VERBOSE,
)


def extract_texthooker(
    data: bytes, filename: str, options: Mapping[str, object]
) -> list[ExtractedSegment]:
    text = decode_japanese(data).replace("\r\n", "\n").replace("\r", "\n")
    sentences: list[ExtractedSentence] = []
    prev: str | None = None
    for raw in text.split("\n"):
        line = _TIMESTAMP.sub("", raw, count=1).strip(" \t　")
        if not line or line == prev:  # hookers re-emit the same line; drop consecutive repeats
            continue
        prev = line
        sentences.append(ExtractedSentence(line))
    if not sentences:
        return []
    return [ExtractedSegment("whole", default_title(filename), tuple(sentences))]
