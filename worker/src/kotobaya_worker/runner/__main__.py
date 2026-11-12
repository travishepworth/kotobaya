"""Entry point: health endpoint on a thread, runner loop on the main thread."""

from __future__ import annotations

import logging
import os
import signal
import threading

from kotobaya_worker import health
from kotobaya_worker.importers.jpdb_history.apply import make_apply_plan, make_fetch_blob
from kotobaya_worker.importers.jpdb_history.handler import make_jpdb_history_import
from kotobaya_worker.jobs.dict_refresh import make_dict_refresh
from kotobaya_worker.runner.core import Registry, Runner, RunnerConfig


def build_registry() -> Registry:
    """Later tickets register their handlers here (dict-refresh, media-parse, tts, ...)."""
    reg = Registry()
    database_url = os.environ["DATABASE_URL"]
    reg.register("dict-refresh", make_dict_refresh(database_url))
    reg.register(
        "jpdb-history-import",
        make_jpdb_history_import(database_url, make_fetch_blob(), make_apply_plan(database_url)),
    )
    return reg


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    threading.Thread(target=health.serve, daemon=True).start()
    Runner(RunnerConfig.from_env(), build_registry()).run_forever(stop)


if __name__ == "__main__":
    main()
