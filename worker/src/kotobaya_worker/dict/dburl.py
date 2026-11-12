"""Convert the JDBC-form ``DATABASE_URL`` into a libpq conninfo string."""

from __future__ import annotations

from urllib.parse import parse_qsl, unquote, urlsplit

from psycopg.conninfo import make_conninfo

_PASSTHROUGH = {"sslmode", "sslrootcert", "connect_timeout", "application_name"}


def to_conninfo(database_url: str) -> str:
    """Accept ``jdbc:postgresql://host:5432/db?user=..&password=..`` (and plain ``postgresql://``)."""
    url = database_url.strip()
    url = url.removeprefix("jdbc:")
    parts = urlsplit(url)
    if parts.scheme not in ("postgresql", "postgres"):
        raise ValueError(f"unsupported DATABASE_URL scheme: {parts.scheme!r}")
    params: dict[str, str] = {}
    if parts.hostname:
        params["host"] = parts.hostname
    if parts.port:
        params["port"] = str(parts.port)
    dbname = unquote(parts.path.lstrip("/"))
    if dbname:
        params["dbname"] = dbname
    if parts.username:
        params["user"] = unquote(parts.username)
    if parts.password:
        params["password"] = unquote(parts.password)
    for key, value in parse_qsl(parts.query, keep_blank_values=True):
        if key in ("user", "password"):
            params[key] = value
        elif key == "ssl" and value.lower() == "true":
            params.setdefault("sslmode", "require")
        elif key in _PASSTHROUGH:
            params[key] = value
    return make_conninfo(**params)
