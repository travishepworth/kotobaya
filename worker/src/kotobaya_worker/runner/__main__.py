"""Entry point: health endpoint on a thread, runner loop on the main thread."""

from __future__ import annotations

import logging
import os
import signal
import threading

from kotobaya_worker import health
from kotobaya_worker.jobs.dict_refresh import make_dict_refresh
from kotobaya_worker.runner.core import Registry, Runner, RunnerConfig


def build_registry() -> Registry:
    """Later tickets register their handlers here (dict-refresh, media-parse, tts, ...)."""
    reg = Registry()
    reg.register("dict-refresh", make_dict_refresh(os.environ["DATABASE_URL"]))
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
