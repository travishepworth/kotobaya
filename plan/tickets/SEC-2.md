# SEC-2 — Worker SSRF guard on URL import (from T609 S-02)

- Phase: P6 · Model: sonnet · Risk: med
- Deps: T609 (MERGED)
- Branch: t/SEC-2-worker-ssrf
- Owned paths (only edit these): `worker/src/kotobaya_worker/extract/html/`, `worker/tests/extract/`
- Module(s) for CI: worker

## Read
- docs/SECURITY.md finding S-02 (row and proposed fix SEC-2)

## Do
- Before each request hop (initial and every redirect), resolve the host and refuse loopback, private (RFC1918, ULA),
  link-local (incl. 169.254.169.254), CGNAT 100.64/10, multicast, unspecified and reserved addresses, IPv4 and IPv6
  (incl. IPv4-mapped IPv6). Connect to the checked IP (or otherwise close the DNS-rebinding window), preserving Host/SNI.
- Handle redirects manually with a hop cap; keep existing scheme checks and timeouts.
- Refusals surface as a clear job error (no silent drop).
- Use stdlib `ipaddress`/`socket`; no new deps unless unavoidable (then justify in HANDOFF).

## Acceptance
- [ ] Tests: direct private IP, hostname resolving to private IP (monkeypatched resolver), redirect to a private IP, IPv4-mapped IPv6 → all refused; public IP allowed.
- [ ] `make check MODULE=worker` green.
