# SRV-404LOG — unknown-route 404 request logged twice

- Phase: P9 hotfix 0.1.11 · Model: haiku · Risk: low
- Deps: none
- Branch: t/SRV-404LOG
- Owned paths (only edit these): server/src/main/kotlin/** request-logging plugin / status pages, server/src/test/**
- Module(s) for CI: server

## Evidence
Live 0.1.10: `GET /api/v1/version` (no such route) → 404; logger kotobaya.request emitted two identical lines with the same request_id (02:30:30.616038Z and .616373Z). Other statuses (200/401) log once.

## Do
Make every request log exactly once, including unmatched routes (likely the StatusPages not-found handler and the request logger both fire, or the logger is installed on two pipelines).

## Acceptance
- [ ] Test: an unknown route produces exactly one kotobaya.request log event (status 404); a known route still one.
- [ ] make check MODULE=server green.
