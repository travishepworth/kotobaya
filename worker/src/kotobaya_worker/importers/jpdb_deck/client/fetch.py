"""Rate-limited, disk-cached HTTP fetcher (stdlib only). The API key never enters a cache key/file."""

from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.request
from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager
from pathlib import Path
from typing import Any

MIN_INTERVAL_S = 2.0
USER_AGENT = "kotobaya-worker/0"

Opener = Callable[..., AbstractContextManager[Any]]


def default_cache_dir() -> Path:
    env = os.environ.get("JPDB_CACHE_DIR")
    return Path(env) if env else Path.home() / ".cache" / "kotobaya-jpdb"


class CachedFetcher:
    def __init__(
        self,
        cache_dir: Path | None = None,
        *,
        min_interval: float = MIN_INTERVAL_S,
        max_age: float | None = None,
        refresh: bool = False,
        opener: Opener = urllib.request.urlopen,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
        wall: Callable[[], float] = time.time,
    ) -> None:
        self._dir = cache_dir if cache_dir is not None else default_cache_dir()
        self._min_interval = min_interval
        self._max_age = max_age  # seconds; None = reuse forever
        self._refresh = refresh
        self._opener = opener
        self._clock = clock
        self._sleeper = sleeper
        self._wall = wall
        self._last_call: float | None = None

    @staticmethod
    def cache_key(url: str, data: bytes | None) -> str:
        h = hashlib.sha256()
        h.update(b"POST " if data is not None else b"GET ")
        h.update(url.encode("utf-8"))
        h.update(b"\n")
        h.update(data or b"")
        return h.hexdigest()

    def get(
        self,
        url: str,
        *,
        data: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        accept: Callable[[str], bool] | None = None,
    ) -> str:
        """`headers` (e.g. Authorization) are sent but never cached or hashed.

        `accept` validates a body: a cached body it rejects is a cache miss, and a fetched body it
        rejects is returned but never written to the cache.
        """
        path = self._dir / f"{self.cache_key(url, data)}.json"
        if not self._refresh and path.exists():
            try:
                entry = json.loads(path.read_text(encoding="utf-8"))
                age = self._wall() - float(entry["fetched_at"])
                body = str(entry["body"])
                if (self._max_age is None or age <= self._max_age) and (
                    accept is None or accept(body)
                ):
                    return body
            except (ValueError, KeyError, TypeError, OSError):
                pass  # unreadable cache entry: refetch
        if self._last_call is not None:
            wait = self._min_interval - (self._clock() - self._last_call)
            if wait > 0:
                self._sleeper(wait)
        req = urllib.request.Request(
            url, data=data, headers={"User-Agent": USER_AGENT, **(headers or {})}
        )
        try:
            with self._opener(req, timeout=30) as resp:
                body = resp.read().decode("utf-8")
        finally:
            self._last_call = self._clock()
        if accept is not None and not accept(body):
            return str(body)
        self._dir.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"url": url, "fetched_at": self._wall(), "body": body}),
            encoding="utf-8",
        )
        return str(body)
