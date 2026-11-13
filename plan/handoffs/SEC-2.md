# HANDOFF SEC-2

STATUS: DONE
BRANCH: t/SEC-2-worker-ssrf
CHECK: `make check MODULE=worker` → green

## Changed (≤ 6 lines)
- worker/src/kotobaya_worker/extract/html/__init__.py: `default_fetcher` rewritten on http.client. Redirects are manual (cap 5), and each hop is scheme-checked, resolved and refused if any address is non-public (private, loopback, link-local, CGNAT, multicast, reserved, unspecified, v4-mapped, NAT64, 6to4). It then connects to the checked IP, with Host and SNI kept as the hostname.
- Removed `_SafeRedirect` (urllib). Refusals raise `FetchError`.
- worker/tests/extract/test_extract_books_web.py: SSRF tests, with fake DNS and a local server.

## Acceptance → tests
- private literal, v4-mapped v6 → test_ssrf_private_literal_refused
- hostname resolving to private → test_ssrf_hostname_resolving_to_private_refused
- redirect to private → test_ssrf_redirect_to_private_refused (plus scheme and loop-cap tests)
- public allowed and pinned → test_public_address_allowed_and_pinned

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A custom `fetch` passed to `extract_url` bypasses the guard. That is intended for tests; production uses `default_fetcher`.
- Non-200 final responses now raise FetchError, where urllib raised HTTPError (a URLError) and wrapped it the same way.
