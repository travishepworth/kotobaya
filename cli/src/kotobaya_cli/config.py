"""Daemon settings: env beats ~/.config/kotobaya/config.toml (waybar/systemd have no shell env)."""

from __future__ import annotations

import math
import shlex
import subprocess
import tomllib
from collections.abc import Mapping
from pathlib import Path

from kotobaya_cli.api import ENV_TOKEN, ENV_URL, CliError, Settings, normalize_url

TOKEN_CMD_TIMEOUT_S = 10.0
ENV_KEEP_GOING_IDLE = "KOTOBAYA_KEEP_GOING_IDLE_S"
KEEP_GOING_IDLE_S = 600.0  # ADR-039: keep going ends after this many seconds without an answer


def default_config_path(env: Mapping[str, str]) -> Path:
    base = env.get("XDG_CONFIG_HOME") or str(Path(env.get("HOME", "~")).expanduser() / ".config")
    return Path(base) / "kotobaya" / "config.toml"


def read_config(path: Path) -> dict[str, object]:
    """The parsed config file, or {} when it does not exist."""
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        return {}
    except OSError as exc:
        raise CliError(f"cannot read {path}: {exc.strerror}") from exc
    try:
        return tomllib.loads(raw.decode())
    except (tomllib.TOMLDecodeError, UnicodeDecodeError) as exc:
        raise CliError(f"invalid config {path}: {exc}") from exc


def _str_key(config: Mapping[str, object], key: str) -> str:
    value = config.get(key)
    if value is None:
        return ""
    if not isinstance(value, str):
        raise CliError(f"config: {key} must be a string")
    return value.strip()


def _token_from_config(config: Mapping[str, object]) -> str:
    token_file = _str_key(config, "token_file")
    token_cmd = _str_key(config, "token_cmd")
    if token_file and token_cmd:
        raise CliError("config: set only one of token_file and token_cmd")
    if token_file:
        path = Path(token_file).expanduser()
        try:
            return path.read_text().strip()
        except OSError as exc:
            raise CliError(f"cannot read token_file {path}: {exc.strerror}") from exc
    if token_cmd:
        try:
            proc = subprocess.run(
                shlex.split(token_cmd),
                capture_output=True,
                text=True,
                timeout=TOKEN_CMD_TIMEOUT_S,
                check=False,
            )
        except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
            raise CliError(f"token_cmd failed ({type(exc).__name__})") from exc
        if proc.returncode != 0:
            raise CliError(f"token_cmd exited with {proc.returncode}")
        return proc.stdout.strip()
    return ""


def load_keep_going_idle(env: Mapping[str, str], config_path: Path | None = None) -> float:
    """Seconds without an answer, undo or skip after which keep going ends (ADR-039).
    Precedence: KOTOBAYA_KEEP_GOING_IDLE_S > keep_going_idle_s in config.toml > 600."""
    config = read_config(config_path or default_config_path(env))
    raw = env.get(ENV_KEEP_GOING_IDLE, "").strip()
    value: object = raw if raw else config.get("keep_going_idle_s")
    if value is None:
        return KEEP_GOING_IDLE_S
    try:
        seconds = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError) as exc:
        raise CliError(f"keep_going_idle_s must be a number of seconds, got {value!r}") from exc
    if isinstance(value, bool) or not math.isfinite(seconds) or seconds <= 0:
        raise CliError(f"keep_going_idle_s must be a positive number of seconds, got {value!r}")
    return seconds


def load_daemon_settings(
    server: str | None, env: Mapping[str, str], config_path: Path | None = None
) -> Settings:
    """Resolve url and token. Precedence: --server / KOTOBAYA_URL / KOTOBAYA_TOKEN > config.toml."""
    config = read_config(config_path or default_config_path(env))
    url = (server or env.get(ENV_URL, "") or _str_key(config, "url")).strip()
    if not url:
        raise CliError(f"no server: set {ENV_URL} or url in config.toml")
    token = env.get(ENV_TOKEN, "").strip() or _token_from_config(config)
    if not token:
        raise CliError(f"no token: set {ENV_TOKEN}, token_file or token_cmd")
    return Settings(base_url=normalize_url(url), token=token)
