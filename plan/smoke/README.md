# Kotobaya smoke test

`smoke.sh` (bash + curl + jq) exercises the API through the web origin, using the same
`/api/v1` paths and headers as the SPA. Output is one `PASS|FAIL|SKIP name reason` line per
check; exit status is nonzero if any check FAILs.

## Run

    export KOTOBAYA_URL=https://kotobaya.batk.me      # default
    export KOTOBAYA_TOKEN=kbp_...                     # personal token (Settings > Tokens)
    plan/smoke/smoke.sh

Auth, one of:
- `KOTOBAYA_TOKEN`: bearer token. Needs scopes `read`, `write`, `import` and `review`
  (`review` is required by `POST /review/answer`; without it the grading checks SKIP).
  Create it without a device: `GET /sync/pull` acks `since` on a bound device.
- `KOTOBAYA_PASSWORD` (+ `KOTOBAYA_TOTP` if TOTP is on): session login via `POST /auth/login`
  with a cookie jar; writes send `X-Kotobaya-Csrf: 1`.

Other env: `SMOKE_VERSION` (expected server version, default `0.1.10`), `SMOKE_JPDB_DECK`
(a jpdb deck id such as `anime/5370`, see below), `SMOKE_CREATED_FILE` (created-ids path).

## Safety

- Everything written is a throwaway named `smoke-<UTC timestamp>`: a user deck, a JLPT list
  deck (the first of N5..N1 that you have not enabled; if all are enabled the build check
  SKIPs), and one vocab note linked to 食べる (the next free 食べ* entry if you own 食べる).
- Ids are recorded in `smoke-created-<ts>.txt` (current directory) before each create. An EXIT
  trap deletes them via `DELETE /notes|decks|media/{id}`; a failed delete is reported. Retry with
  `plan/smoke/smoke.sh --cleanup smoke-created-<ts>.txt`.
- Learning writes go through the op-apply path only: note/card/deck creation and
  `POST /review/answer` (emits `review.logged`) on the throwaway card. Your cards are never
  graded. The review_log row of the throwaway card stays (history is never deleted).
- The jpdb history job is only read. `SMOKE_JPDB_DECK` imports one jpdb deck as media titled
  `smoke-<ts>` (removed at exit); it SKIPs when a media with that `jpdb_id` already exists,
  because the import would update it.
- The grading step uses `/review/answer` (what the SPA calls), not `POST /sync/push`: push needs a
  device-bound token and client-minted HLC ops. The op is then verified via `GET /sync/pull`.

## Checks

health/version, meta, auth/session, decks list, create deck, `/deck-lists` N5..N1 counts,
throwaway list deck fills (polled 90 s), last jpdb-history job (`vocabulary_carded>0`), optional
jpdb deck import, dictionary search + entry for 食べる, create note, note detail, card search for
食べる / たべる / タベル (`query=<term> nid:<note>`), collapsed rows (the web groups cards by
`note_id`; all cards of the note must form one row), review queue, grade, graded card not served
again, review history, sync pull has `review.logged` and `note.created`, `/words/hot`,
`/words/leeches`, `/kanji/hot`, `POST /client-errors` 202 and a 21-item batch 413.

## Finding the synthetic client error

    kubectl -n kotobaya logs deploy/<api-deployment> --since=15m | grep 'smoke-test <ts>'

The script prints the exact grep (logger `kotobaya.client_error`, level WARN, message
`smoke-test <ts>`). Not run against production; only syntax-checked (`shellcheck` clean).
