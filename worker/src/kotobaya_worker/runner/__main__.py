"""Entry point: health endpoint on a thread, runner loop on the main thread."""

from __future__ import annotations

import logging
import signal
import threading

from kotobaya_worker import health
from kotobaya_worker.runner.core import Registry, Runner, RunnerConfig


def build_registry() -> Registry:
    """Later tickets register their handlers here (dict-refresh, media-parse, tts, ...)."""
    return Registry()


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    threading.Thread(target=health.serve, daemon=True).start()
    Runner(RunnerConfig.from_env(), build_registry()).run_forever(stop)


if __name__ == "__main__":
    main()
