"""Minimal S3 client (path-style, SigV4, stdlib only) for content-addressed blobs.

Configured from ``S3_ENDPOINT``, ``S3_BUCKET``, ``S3_ACCESS_KEY``, ``S3_SECRET_KEY``. With no
access key the requests are unsigned (the SeaweedFS dev store has no auth). Blobs are stored
under ``blobs/<sha256>``.
"""

from __future__ import annotations

import hashlib
import hmac
import http.client
import os
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import quote, urlsplit

REGION = "us-east-1"


@dataclass(frozen=True)
class S3Config:
    endpoint: str
    bucket: str
    access_key: str = ""
    secret_key: str = ""

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> S3Config:
        e = os.environ if env is None else env
        return S3Config(
            endpoint=e["S3_ENDPOINT"],
            bucket=e["S3_BUCKET"],
            access_key=e.get("S3_ACCESS_KEY", ""),
            secret_key=e.get("S3_SECRET_KEY", ""),
        )


def blob_key(sha256: str) -> str:
    return f"blobs/{sha256}"


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def sign_headers(
    cfg: S3Config, method: str, path: str, host: str, payload_sha256: str, now: datetime
) -> dict[str, str]:
    """SigV4 headers for one request (empty when no access key is configured)."""
    if not cfg.access_key:
        return {}
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    day = amz_date[:8]
    headers = {"host": host, "x-amz-content-sha256": payload_sha256, "x-amz-date": amz_date}
    signed = ";".join(sorted(headers))
    canonical = "\n".join(
        [
            method,
            quote(path, safe="/-_.~"),
            "",
            "".join(f"{k}:{headers[k]}\n" for k in sorted(headers)),
            signed,
            payload_sha256,
        ]
    )
    scope = f"{day}/{REGION}/s3/aws4_request"
    to_sign = "\n".join(
        ["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode()).hexdigest()]
    )
    k = _hmac(("AWS4" + cfg.secret_key).encode(), day)
    for part in (REGION, "s3", "aws4_request"):
        k = _hmac(k, part)
    sig = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
    out = {k_: v for k_, v in headers.items() if k_ != "host"}
    out["Authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={cfg.access_key}/{scope}, SignedHeaders={signed}, "
        f"Signature={sig}"
    )
    return out


_EMPTY_SHA = hashlib.sha256(b"").hexdigest()


class S3Client:
    def __init__(self, cfg: S3Config) -> None:
        self.cfg = cfg
        u = urlsplit(cfg.endpoint)
        self._https = u.scheme == "https"
        self._host = u.netloc

    def _request(self, method: str, path: str, body: Path | None = None, sha: str = "") -> int:
        payload = sha or _EMPTY_SHA
        headers = sign_headers(self.cfg, method, path, self._host, payload, datetime.now(UTC))
        cls = http.client.HTTPSConnection if self._https else http.client.HTTPConnection
        conn = cls(self._host, timeout=300)
        try:
            if body is None:
                headers["Content-Length"] = "0"
                conn.request(method, quote(path, safe="/-_.~"), headers=headers)
            else:
                headers["Content-Length"] = str(body.stat().st_size)
                with body.open("rb") as f:
                    conn.request(method, quote(path, safe="/-_.~"), body=f, headers=headers)
            resp = conn.getresponse()
            resp.read()
            return resp.status
        finally:
            conn.close()

    def ensure_bucket(self) -> None:
        path = f"/{self.cfg.bucket}"
        if self._request("HEAD", path) == 200:
            return
        status = self._request("PUT", path)
        if status not in (200, 409):  # 409: created concurrently
            raise RuntimeError(f"cannot create bucket {self.cfg.bucket}: HTTP {status}")

    def put_blob(self, file: Path, sha256: str) -> None:
        """Upload ``file`` as ``blobs/<sha256>`` unless already there."""
        path = f"/{self.cfg.bucket}/{blob_key(sha256)}"
        if self._request("HEAD", path) == 200:
            return
        status = self._request("PUT", path, body=file, sha=sha256)
        if status not in (200, 201):
            raise RuntimeError(f"upload of {sha256} failed: HTTP {status}")
