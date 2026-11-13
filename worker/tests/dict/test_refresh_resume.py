"""F-12: a resumed dict-refresh always ends with the device package published, exactly once."""

from __future__ import annotations

import gzip
import io
import json
import zipfile
from pathlib import Path
from typing import Any, cast

import psycopg
import pytest

from kotobaya_worker.dict.dburl import to_conninfo
from kotobaya_worker.dict.fetch import JMDICT_URL, KANJIDIC2_URL, KRAD_URL
from kotobaya_worker.dict.package import release as release_mod
from kotobaya_worker.dict.package.source import SCOPES
from kotobaya_worker.jobs import dict_refresh
from kotobaya_worker.jobs.dict_refresh import DictSources, make_dict_refresh
from kotobaya_worker.runner.core import JobContext

FIXTURES = Path(__file__).resolve().parents[3] / "fixtures" / "dict"


class Ctx:
    def __init__(self) -> None:
        self.payload: dict[str, Any] = {}

    def progress(self, fraction: float, message: str | None = None) -> None:
        pass

    def check_cancelled(self) -> None:
        pass


def _blobs() -> dict[str, bytes]:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("kradfile", "# c\n亜 : ｜ 一 口\n".encode("euc-jp"))
    return {
        JMDICT_URL: gzip.compress((FIXTURES / "JMdict_subset.xml").read_bytes()),
        KANJIDIC2_URL: gzip.compress((FIXTURES / "kanjidic2_subset.xml").read_bytes()),
        KRAD_URL: buf.getvalue(),
    }


def _state(url: str) -> tuple[list[Any], int]:
    with psycopg.connect(to_conninfo(url)) as c:
        rows = c.execute("SELECT id, artifacts FROM ref.dict_release ORDER BY id").fetchall()
        n = c.execute("SELECT count(*) FROM ref.dict_release").fetchone()
    return rows, (n[0] if n else 0)


def test_resume_after_load_publishes_package_once(
    db_url: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    blobs = _blobs()

    def fetch(url: str, dest: Path) -> None:
        dest.write_bytes(blobs[url])

    handler = make_dict_refresh(
        db_url,
        sources=lambda: DictSources.from_json(json.dumps({})),
        s3=lambda: None,
        cache_dir=tmp_path,
        fetch=fetch,
    )
    ctx = cast(JobContext, Ctx())

    real = release_mod.build_release

    def crash(*a: Any, **k: Any) -> Any:
        raise RuntimeError("worker killed after load")

    monkeypatch.setattr(dict_refresh, "build_release", crash)
    with pytest.raises(RuntimeError):
        handler(ctx)
    rows, n = _state(db_url)
    assert n == 1 and rows[0][1] in ([], None)  # loaded, no package

    monkeypatch.setattr(dict_refresh, "build_release", real)
    out = handler(ctx)
    assert out["release_id"] == 1
    rows, n = _state(db_url)
    assert n == 1  # the resume reused the loaded release
    assert {a["scope"] for a in rows[0][1] if a["kind"] == "full"} == set(SCOPES)

    published = rows[0][1]
    again = handler(ctx)  # fully completed: no-op
    assert again["release_id"] is None
    rows, n = _state(db_url)
    assert n == 1 and rows[0][1] == published
