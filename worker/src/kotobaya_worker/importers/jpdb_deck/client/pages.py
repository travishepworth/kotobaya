"""Pages source: public `<deck>/vocabulary-list` pages (no key needed). Scraped, so tolerant."""

from __future__ import annotations

import html as htmllib
import re
import urllib.parse

from .fetch import CachedFetcher
from .models import DeckVocabulary, DeckWord, SkippedRow

BASE = "https://jpdb.io"
ENTRY_MARK = '<div class="entry">'
_LINK = re.compile(r'href="/vocabulary/(\d+)/([^"#?]*)')
_ANCHOR = re.compile(r'<a href="/vocabulary/[^>]*>(.*?)</a>', re.DOTALL)
_RUBY = re.compile(r"<ruby>([^<]*)(?:<rt>([^<]*)</rt>)?</ruby>")
_RANK = re.compile(r'class="tag tooltip"[^>]*>Top (\d+)<')
_OCC = re.compile(r'opacity: 0\.5; margin-top: 1rem;">(\d+)</div>')
_NEXT = re.compile(r'href="([^"]*\?[^"]*offset=\d+)(?:#[^"]*)?"[^>]*>\s*Next page')
_H1 = re.compile(r"<h1[^>]*>(.*?)</h1>", re.DOTALL)
_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.DOTALL)
_TAGS = re.compile(r"<[^>]+>")
MAX_PAGES = 1000


def deck_path(jpdb_id: str) -> str:
    """`anime/5370` -> itself; bare numeric id -> `deck/<id>` (UNVERIFIED path form)."""
    return f"deck/{jpdb_id}" if jpdb_id.isdigit() else jpdb_id.strip("/")


def parse_vocab_list_html(page: str) -> tuple[list[DeckWord], list[SkippedRow], int]:
    """Return (words, skipped, raw_row_count); words + skipped always equals raw rows."""
    words: list[DeckWord] = []
    skipped: list[SkippedRow] = []
    chunks = page.split(ENTRY_MARK)[1:]
    for chunk in chunks:
        m = _LINK.search(chunk)
        if not m:
            skipped.append(SkippedRow(chunk[:300], "no vocabulary link with numeric vid"))
            continue
        spelling = urllib.parse.unquote(m.group(2))
        if not spelling:
            skipped.append(SkippedRow(chunk[:300], "empty spelling slug"))
            continue
        reading = ""
        anchor = _ANCHOR.search(chunk)
        if anchor:
            inner = _RUBY.sub(lambda r: r.group(2) or r.group(1), anchor.group(1))
            reading = htmllib.unescape(_TAGS.sub("", inner)).strip()
        rank = _RANK.search(chunk)
        occ = _OCC.findall(chunk)
        words.append(
            DeckWord(
                vid=int(m.group(1)),
                spelling=spelling,
                reading=reading or spelling,
                occurrence_count=int(occ[-1]) if occ else None,
                global_rank_bucket=int(rank.group(1)) if rank else None,
            )
        )
    return words, skipped, len(chunks)


def next_page_url(page: str, current: str) -> str | None:
    m = _NEXT.search(page)
    return urllib.parse.urljoin(current, htmllib.unescape(m.group(1))) if m else None


def parse_title(page: str) -> str | None:
    for pat in (_H1, _TITLE):
        m = pat.search(page)
        if m:
            text = htmllib.unescape(_TAGS.sub("", m.group(1))).strip()
            if text:
                return text
    return None


class PagesDeckSource:
    def __init__(self, fetcher: CachedFetcher, base: str = BASE) -> None:
        self._f = fetcher
        self._base = base.rstrip("/")

    def fetch_title(self, jpdb_id: str) -> str | None:
        return parse_title(self._f.get(f"{self._base}/{deck_path(jpdb_id)}"))

    def fetch_deck(self, jpdb_id: str) -> DeckVocabulary:
        path = deck_path(jpdb_id)
        title = self.fetch_title(jpdb_id) or jpdb_id
        out = DeckVocabulary(title=title)
        url: str | None = f"{self._base}/{path}/vocabulary-list?sort_by=by-frequency-local"
        seen: set[str] = set()
        while url is not None and url not in seen and len(seen) < MAX_PAGES:
            seen.add(url)
            page = self._f.get(url)
            words, skipped, raw = parse_vocab_list_html(page)
            out.words.extend(words)
            out.skipped.extend(skipped)
            out.raw_rows += raw
            url = next_page_url(page, url)
        return out
