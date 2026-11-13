"""API source (needs JPDB_API_KEY). Endpoint/response layout UNVERIFIED; falls back to Pages."""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from .fetch import CachedFetcher
from .models import DeckVocabulary, DeckWord, JpdbDeckSource, SkippedRow
from .pages import PagesDeckSource

log = logging.getLogger(__name__)
API_BASE = "https://jpdb.io/api/v1"


class ApiSchemaMismatch(Exception):
    pass


def map_api_response(res: object) -> DeckVocabulary:
    """The only place that knows the response layout (assumed, see HUMAN_CHECKS).

    Assumed: {"vocabulary": [[vid, spelling, reading, frequency_rank|null], ...],
              "occurences": [n, ...] (parallel to vocabulary), "title": str (optional)}
    """
    if not isinstance(res, dict) or not isinstance(res.get("vocabulary"), list):
        raise ApiSchemaMismatch("response has no 'vocabulary' list")
    rows: list[Any] = res["vocabulary"]
    occ = res.get("occurences")
    if occ is not None and not (isinstance(occ, list) and len(occ) == len(rows)):
        raise ApiSchemaMismatch("'occurences' is not parallel to 'vocabulary'")
    out = DeckVocabulary(title=str(res.get("title") or ""), raw_rows=len(rows))
    for i, row in enumerate(rows):
        ok = (
            isinstance(row, list)
            and len(row) >= 3
            and isinstance(row[0], int)
            and isinstance(row[1], str)
            and isinstance(row[2], str)
        )
        if not ok:
            out.skipped.append(SkippedRow(json.dumps(row, ensure_ascii=False)[:300], "bad row"))
            continue
        rank = row[3] if len(row) > 3 and isinstance(row[3], int) else None
        n = occ[i] if occ is not None and isinstance(occ[i], int) else None
        out.words.append(DeckWord(row[0], row[1], row[2] or row[1], n, rank))
    if rows and not out.words:
        raise ApiSchemaMismatch("no row matched the assumed layout")
    return out


class ApiDeckSource:
    def __init__(
        self,
        fetcher: CachedFetcher,
        api_key: str,
        fallback: PagesDeckSource,
        base: str = API_BASE,
    ) -> None:
        self._f = fetcher
        self._key = api_key
        self._fallback = fallback
        self._base = base

    def fetch_deck(self, jpdb_id: str) -> DeckVocabulary:
        try:
            if not jpdb_id.isdigit():
                raise ApiSchemaMismatch("API needs a numeric deck id")
            body = json.dumps({"id": int(jpdb_id), "fetch_occurences": True}, sort_keys=True)
            raw = self._f.get(
                f"{self._base}/deck/list-vocabulary",
                data=body.encode(),
                headers={
                    "Authorization": f"Bearer {self._key}",
                    "Content-Type": "application/json",
                },
            )
            try:
                res = json.loads(raw)
            except ValueError as e:
                raise ApiSchemaMismatch(f"not JSON: {e}") from e
            deck = map_api_response(res)
        except ApiSchemaMismatch as e:
            log.warning("jpdb API path unusable (%s); falling back to pages", e)
            return self._fallback.fetch_deck(jpdb_id)
        if not deck.title:
            deck.title = self._try_title(jpdb_id) or jpdb_id
        return deck

    def _try_title(self, jpdb_id: str) -> str | None:
        try:
            return self._fallback.fetch_title(jpdb_id)
        except Exception:  # noqa: BLE001
            return None


def make_source(fetcher: CachedFetcher | None = None) -> JpdbDeckSource:
    f = fetcher or CachedFetcher()
    pages = PagesDeckSource(f)
    key = os.environ.get("JPDB_API_KEY")
    return ApiDeckSource(f, key, pages) if key else pages
