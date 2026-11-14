"""Pages source: public `<deck>/vocabulary-list` pages (no key needed). Scraped, so tolerant."""

from __future__ import annotations

import html as htmllib
import re
import urllib.error
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


_NOT_DECKS = frozenset({"vocabulary", "kanji", "api", "deck"})
_KIND_ID = re.compile(r"^([a-z][a-z-]*)/(\d+)$")
_URL_PATH = re.compile(
    r"^(?:https?://(?:www\.)?jpdb\.io)?/?(.*?)/?(?:\?.*)?(?:#.*)?$", re.IGNORECASE
)


def normalize_deck_id(raw: str) -> str:
    """Normalise a deck reference to `<kind>/<id>` (e.g. `visual-novel/3913`).

    Accepts full jpdb URLs (with or without a trailing `/vocabulary-list` or other sub-path) and
    `<kind>/<id>` paths. A bare number is rejected: there is no verified jpdb URL for it.
    """
    text = raw.strip()
    if text.isdigit():
        raise ValueError(
            f"jpdb deck {text!r}: a bare number is ambiguous; use the deck URL, "
            "e.g. https://jpdb.io/visual-novel/3913/vocabulary-list"
        )
    m = _URL_PATH.match(text)
    parts = [p for p in (m.group(1) if m else "").split("/") if p]
    if len(parts) >= 2:
        cand = f"{parts[0].lower()}/{parts[1]}"
        if _KIND_ID.match(cand) and parts[0].lower() not in _NOT_DECKS:
            return cand
    raise ValueError(f"jpdb deck {raw!r}: expected a jpdb URL or <kind>/<id> such as anime/5370")


def deck_slug(raw: str) -> str | None:
    """Title slug from a jpdb URL like `.../visual-novel/3913/fatal-twelve/vocabulary-list`."""
    m = _URL_PATH.match(raw.strip())
    parts = [p for p in (m.group(1) if m else "").split("/") if p]
    if len(parts) >= 3 and parts[2].lower() != "vocabulary-list":
        return urllib.parse.quote(urllib.parse.unquote(parts[2]), safe="-_.~")
    return None


def has_entries(page: str) -> bool:
    return ENTRY_MARK in page


def deck_path(jpdb_id: str) -> str:
    return normalize_deck_id(jpdb_id)


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


_GENERIC = {"vocabulary list", "vocabulary", "jpdb"}


def parse_title(page: str) -> str | None:
    for pat in (_H1, _TITLE):
        m = pat.search(page)
        if m:
            text = htmllib.unescape(_TAGS.sub("", m.group(1))).strip()
            text = re.sub(r"\s*[-|]\s*jpdb\s*$", "", text, flags=re.IGNORECASE).strip()
            if text and text.lower() not in _GENERIC:
                return text
    return None


class PagesDeckSource:
    def __init__(self, fetcher: CachedFetcher, base: str = BASE) -> None:
        self._f = fetcher
        self._base = base.rstrip("/")

    def fetch_title(self, jpdb_id: str) -> str | None:
        """Title from the first vocabulary-list page (never the bare deck URL, which 404s)."""
        try:
            return parse_title(self._f.get(self._first_url(jpdb_id), accept=has_entries))
        except Exception:  # noqa: BLE001  a title failure must never fail the job
            return None

    def _first_url(self, jpdb_id: str) -> str:
        """List URL WITH the title slug (the slugless one returns the 0-row overview page)."""
        path = deck_path(jpdb_id)
        slug = deck_slug(jpdb_id)
        if slug is None:
            overview_url = f"{self._base}/{path}/vocabulary-list"
            overview = self._f.get(overview_url)
            m = re.search(rf'href="/{re.escape(path)}/([^/"?#]+)/vocabulary-list"', overview)
            if not m:
                raise RuntimeError(f"jpdb deck {path}: no vocabulary-list link on {overview_url}")
            slug = m.group(1)
        return f"{self._base}/{path}/{slug}/vocabulary-list?sort_by=by-frequency-local"

    def fetch_deck(self, jpdb_id: str) -> DeckVocabulary:
        out = DeckVocabulary(title="")
        url: str | None = self._first_url(jpdb_id)
        seen: set[str] = set()
        while url is not None and url not in seen and len(seen) < MAX_PAGES:
            first = not seen
            seen.add(url)
            try:
                page = self._f.get(url, accept=has_entries if first else None)
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    raise RuntimeError(f"jpdb deck page not found (404): {url}") from e
                raise
            if first:
                if not has_entries(page):
                    raise RuntimeError(f"jpdb deck page has 0 vocabulary rows: {url}")
                out.title = parse_title(page) or jpdb_id
            words, skipped, raw = parse_vocab_list_html(page)
            out.words.extend(words)
            out.skipped.extend(skipped)
            out.raw_rows += raw
            url = next_page_url(page, url)
        return out
