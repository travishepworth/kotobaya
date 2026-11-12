"""Throwaway Postgres 17 (docker) with the V1 schema, initialised with a UTF-8 ctype."""

from __future__ import annotations

import shutil
import subprocess
import time
import uuid
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[3]
V1 = ROOT / "contracts" / "db" / "V1__init.sql"
FIXTURES = ROOT / "fixtures" / "dict"
PG_PASSWORD = "kotobaya"


def _docker_ok() -> bool:
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "info"], check=False, capture_output=True).returncode == 0


class PgServer:
    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port

    def jdbc(self, db: str) -> str:
        return (
            f"jdbc:postgresql://{self.host}:{self.port}/{db}?user=postgres&password={PG_PASSWORD}"
        )

    def admin(self) -> psycopg.Connection[Any]:
        return psycopg.connect(
            host=self.host,
            port=self.port,
            user="postgres",
            password=PG_PASSWORD,
            dbname="postgres",
            autocommit=True,
        )


@pytest.fixture(scope="session")
def pg_server() -> Iterator[PgServer]:
    if not _docker_ok():
        pytest.skip("docker not available")
    name = f"kotobaya-t109b-{uuid.uuid4().hex[:8]}"
    subprocess.run(
        [
            "docker", "run", "-d", "--rm", "--name", name,
            "-e", f"POSTGRES_PASSWORD={PG_PASSWORD}",
            "-e", "POSTGRES_INITDB_ARGS=--encoding=UTF8 --lc-ctype=C.UTF-8",
            "-p", "127.0.0.1::5432",
            "postgres:17",
        ],
        check=True,
        capture_output=True,
    )  # fmt: skip
    try:
        out = subprocess.run(
            ["docker", "port", name, "5432/tcp"], check=True, capture_output=True, text=True
        ).stdout.splitlines()[0]
        server = PgServer("127.0.0.1", int(out.rsplit(":", 1)[1]))
        deadline = time.time() + 90
        while True:
            try:
                time.sleep(2)  # the image restarts once after initdb
                with server.admin() as c:
                    c.execute("select 1")
                time.sleep(1)
                with server.admin() as c:
                    c.execute("select 1")
                break
            except psycopg.OperationalError:
                if time.time() > deadline:
                    raise
        with server.admin() as c:
            c.execute("CREATE DATABASE tpl")
        with psycopg.connect(
            host=server.host, port=server.port, user="postgres", password=PG_PASSWORD, dbname="tpl"
        ) as c:
            c.execute(V1.read_text(encoding="utf-8"))
        yield server
    finally:
        subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)


@pytest.fixture
def db_url(pg_server: PgServer) -> str:
    """JDBC URL of a fresh database cloned from the V1 template."""
    db = "t_" + uuid.uuid4().hex[:10]
    with pg_server.admin() as c:
        c.execute(f'CREATE DATABASE "{db}" TEMPLATE tpl')
    return pg_server.jdbc(db)
