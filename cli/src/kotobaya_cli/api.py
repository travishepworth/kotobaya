"""Settings and generated-client construction. The token is never printed."""

from __future__ import annotations

import os
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass

from kotobaya_client.api.blobs_api import BlobsApi
from kotobaya_client.api.cards_api import CardsApi
from kotobaya_client.api.jobs_api import JobsApi
from kotobaya_client.api.media_api import MediaApi
from kotobaya_client.api.meta_api import MetaApi
from kotobaya_client.api_client import ApiClient
from kotobaya_client.configuration import Configuration

ENV_URL = "KOTOBAYA_URL"
ENV_TOKEN = "KOTOBAYA_TOKEN"
API_PREFIX = "/api/v1"
TOKEN_REJECTED = "token missing or revoked"


class CliError(Exception):
    """A user-facing failure: printed as one line, exit code 1."""


@dataclass(frozen=True)
class Settings:
    base_url: str  # always ends with /api/v1
    token: str  # never printed


@dataclass(frozen=True)
class Api:
    meta: MetaApi
    cards: CardsApi
    media: MediaApi
    blobs: BlobsApi
    jobs: JobsApi


def load_settings(server: str | None, env: Mapping[str, str]) -> Settings:
    """Resolve the server (`--server` wins over KOTOBAYA_URL) and the PAT."""
    url = (server or env.get(ENV_URL, "")).strip().rstrip("/")
    if not url:
        raise CliError(f"no server: pass --server or set {ENV_URL}")
    if not url.startswith(("http://", "https://")):
        raise CliError("server URL must start with http:// or https://")
    if not url.endswith(API_PREFIX):
        url += API_PREFIX
    token = env.get(ENV_TOKEN, "")
    if not token:
        raise CliError(f"{ENV_TOKEN} is not set")
    return Settings(base_url=url, token=token)


def make_api(settings: Settings) -> Api:
    client = ApiClient(Configuration(host=settings.base_url, access_token=settings.token))
    return Api(
        meta=MetaApi(client),
        cards=CardsApi(client),
        media=MediaApi(client),
        blobs=BlobsApi(client),
        jobs=JobsApi(client),
    )


def uuid7(now_ms: int | None = None) -> str:
    """RFC 9562 UUIDv7 (the stdlib only gains uuid7 in Python 3.14)."""
    ms = time.time_ns() // 1_000_000 if now_ms is None else now_ms
    rand = int.from_bytes(os.urandom(10), "big")  # 80 random bits
    rand_a = (rand >> 68) & 0xFFF
    rand_b = rand & ((1 << 62) - 1)
    value = ((ms & ((1 << 48) - 1)) << 80) | (0x7 << 76) | (rand_a << 64) | (0b10 << 62) | rand_b
    return str(uuid.UUID(int=value))
