"""Daemon config: env beats config.toml; token_file and token_cmd."""

from __future__ import annotations

import io
from pathlib import Path

import pytest

from kotobaya_cli.api import CliError
from kotobaya_cli.cli import main
from kotobaya_cli.config import load_daemon_settings


def write(path: Path, text: str) -> Path:
    path.write_text(text)
    return path


def test_config_file_supplies_url_and_token_file(tmp_path: Path) -> None:
    token = write(tmp_path / "tok", "kbp_fromfile\n")
    cfg = write(tmp_path / "c.toml", f'url = "https://k.test"\ntoken_file = "{token}"\n')
    s = load_daemon_settings(None, {}, cfg)
    assert s.base_url == "https://k.test/api/v1"
    assert s.token == "kbp_fromfile"


def test_env_beats_config_file(tmp_path: Path) -> None:
    token = write(tmp_path / "tok", "kbp_fromfile")
    cfg = write(tmp_path / "c.toml", f'url = "https://file.test"\ntoken_file = "{token}"\n')
    env = {"KOTOBAYA_URL": "https://env.test", "KOTOBAYA_TOKEN": "kbp_env"}
    s = load_daemon_settings(None, env, cfg)
    assert (s.base_url, s.token) == ("https://env.test/api/v1", "kbp_env")
    assert (
        load_daemon_settings("https://flag.test", env, cfg).base_url == "https://flag.test/api/v1"
    )


def test_token_cmd_runs_and_failure_is_an_error(tmp_path: Path) -> None:
    cfg = write(tmp_path / "c.toml", 'url = "https://k.test"\ntoken_cmd = "echo kbp_fromcmd"\n')
    assert load_daemon_settings(None, {}, cfg).token == "kbp_fromcmd"
    bad = write(tmp_path / "b.toml", 'url = "https://k.test"\ntoken_cmd = "false"\n')
    with pytest.raises(CliError, match="token_cmd exited"):
        load_daemon_settings(None, {}, bad)


def test_config_errors_are_clear(tmp_path: Path) -> None:
    both = write(tmp_path / "a.toml", 'url = "https://k.test"\ntoken_file = "x"\ntoken_cmd = "y"\n')
    with pytest.raises(CliError, match="only one"):
        load_daemon_settings(None, {}, both)
    with pytest.raises(CliError, match="no server"):
        load_daemon_settings(None, {}, tmp_path / "missing.toml")
    notoken = write(tmp_path / "n.toml", 'url = "https://k.test"\n')
    with pytest.raises(CliError, match="no token"):
        load_daemon_settings(None, {}, notoken)
    broken = write(tmp_path / "x.toml", "url = ")
    with pytest.raises(CliError, match="invalid config"):
        load_daemon_settings(None, {}, broken)


def test_daemon_command_reports_config_errors_without_a_traceback(tmp_path: Path) -> None:
    err = io.StringIO()
    code = main(["daemon", "--config", str(tmp_path / "none.toml")], env={}, stderr=err)
    assert code == 1
    assert "no server" in err.getvalue()
