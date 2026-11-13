"""VOICEVOX text-to-speech job (``tts``)."""

from kotobaya_worker.tts.job import KIND, dedupe_key, make_tts, run_tts

__all__ = ["KIND", "dedupe_key", "make_tts", "run_tts"]
