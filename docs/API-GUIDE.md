# Kotobaya API guide

Task-oriented recipes for the REST API: what to run to do X. The contract is
`contracts/api/openapi.yaml`; every path, method, body field and scope below comes from it, and
each recipe names its `operationId` so you can look it up. Needs `curl` and `jq`.

## Contents

1. [Setup](#1-setup)
2. [Auth and scopes](#2-auth-and-scopes)
3. [Review](#3-review)
4. [Notes and cards](#4-notes-and-cards)
5. [Card search syntax](#5-card-search-syntax)
6. [Decks](#6-decks)
7. [Dictionary](#7-dictionary)
8. [Settings](#8-settings)
9. [Imports, media and jobs](#9-imports-media-and-jobs)
10. [Admin](#10-admin)
11. [Sync, devices and tokens](#11-sync-devices-and-tokens)

Recipe format: `operationId` · required scope · CLI equivalent (if one exists), then the command
and the expected result.

## 1. Setup

The base URL is the deployment origin; the API lives under `/api/v1`. The CLI accepts the origin too.

```sh
export KOTOBAYA_URL=https://kotobaya.example   # origin only, no /api/v1
export KOTOBAYA_TOKEN=kbp_...                  # secret: keep it in env or a secret store, never in git
```

Every call except `GET /health` sends `Authorization: Bearer $KOTOBAYA_TOKEN`. JSON bodies send
`Content-Type: application/json`. Unknown body fields and unknown query parameters are rejected
with `422`, so copy field names exactly.

Recipes that create rows need a UUIDv7 `id`. Define this helper once (bash or zsh):

```sh
uuid7() {
  local ms rnd
  ms="$(date +%s%3N)"
  rnd="$(od -An -N10 -tx1 /dev/urandom | tr -d ' \n')"
  printf '%012x-7%s-%x%s-%s\n' "$ms" "${rnd:0:3}" $((8 + 0x${rnd:3:1} % 4)) "${rnd:4:3}" "${rnd:7:12}" |
    sed -E 's/^([0-9a-f]{8})([0-9a-f]{4})-/\1-\2-/'
}
```

## 2. Auth and scopes

Mint a personal token in the web app under **Settings > Tokens**. Minting needs the `admin` scope,
so it is not done with a token. Pick a name and scopes; the `kbp_...` string is shown once.

| Scope | Allows |
|---|---|
| `read` | All `GET` endpoints and read-only `POST`s (`/parse`, `/blobs/query`). |
| `write` | Create, edit and delete notes, cards, decks, settings, media metadata; `PUT /blobs/{hash}`. |
| `review` | `POST /review/answer`, `/review/undo`, `/drill/session`, `/cards/bulk/schedule`. |
| `import` | Media and jpdb imports, dictionary refresh, FSRS optimize, job cancel, blob upload. |
| `admin` | `/devices` and `/tokens` endpoints. |

A widget or script that only reads and reviews needs `read` + `review`. A missing scope returns
`403 insufficient_scope` with `required_scope` naming it. `401` means the token is missing,
revoked or expired.

## 3. Review

### Get the review queue
`getReviewQueue` · scope `read` · CLI: none (`kotobaya daemon` runs the review stream, see `docs/CLI-DAEMON.md`)
```sh
curl -sS "$KOTOBAYA_URL/api/v1/review/queue?limit=5" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '{counts, cards: [.items[] | {id: .card.id, kind: .card.kind, state: .card.state}]}'
```
Expected: `counts` with `new`, `learning`, `review`; empty `cards` and zero counts means done for today.

### Answer a card
`answerReview` · scope `review` · CLI: none
```sh
CARD=$(curl -sS "$KOTOBAYA_URL/api/v1/review/queue?limit=1" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq -r '.items[0].card.id')
ID=$(uuid7)   # UUIDv7 from section 1; reuse the same id to retry safely (server dedupes)
curl -sS -X POST "$KOTOBAYA_URL/api/v1/review/answer" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"id\":\"$ID\",\"card_id\":\"$CARD\",\"rating\":3,\"button\":\"good\",\"duration_ms\":4200,\"utc_offset_min\":540}"
```
Expected: `200` with `review` and the updated `card`. `rating` is 1-4; send `button` in five-button mode.
A suspended card returns `409 card_not_reviewable`.

### Undo a review
`undoReview` · scope `review` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/review/undo" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"review_id\":\"$ID\"}"
```
Expected: `200` with the card back in its previous state. `review_id` is the `id` you sent to
`/review/answer`; undoing twice is safe (`200`, no change).

## 4. Notes and cards

### Create a vocab note from a dictionary entry
`createNote` · scope `write` · CLI: none
```sh
NT=$(curl -sS "$KOTOBAYA_URL/api/v1/note-types" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq -r '[.items[] | select(.base_type == "vocab")][0].id')
curl -sS -X POST "$KOTOBAYA_URL/api/v1/notes?on_duplicate=return_existing" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"note_type_id\":\"$NT\",\"entry_seq\":1358280,\"fields\":{\"meaning\":\"to eat\"},\"tags\":[\"food\"]}" |
  jq '{id, type, cards: [.cards[].kind]}'
```
Expected: `201` (or `200` with the existing note when `on_duplicate=return_existing`). Field keys must
be the note type's `fields[].key` (`GET /note-types/{id}`); `meaning` is the built-in example key.
Add `"deck_id":"<user deck id>"` to put the note in a deck. `entry_seq` is the JMdict seq.

### Find cards
`listCards` · scope `read` · CLI: `kotobaya cards find 'is:leech' [--json] [--limit N]`
```sh
curl -sS -G "$KOTOBAYA_URL/api/v1/cards" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" \
  --data-urlencode 'query=is:due deck:"Frieren"' --data-urlencode 'sort=-lapses' --data-urlencode 'limit=20' |
  jq '.items[] | {id, kind, state, due_at}'
```
Expected: one line per card, with `next_cursor` in the response when there are more. Query syntax: section 5.

### Suspend, unsuspend, bury and unbury one card
`updateCard` · scope `write` · CLI: none
```sh
curl -sS -X PATCH "$KOTOBAYA_URL/api/v1/cards/$CARD" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' -d '{"suspended":true}'
curl -sS -X PATCH "$KOTOBAYA_URL/api/v1/cards/$CARD" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' -d '{"buried_until":"2026-11-16T10:00:00.000Z"}'
```
Expected: `200` with the card. Use `{"suspended":false}` to unsuspend and `{"buried_until":null}` to unbury.

### Suspend or bury many cards at once
`bulkCards` · scope `write` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/cards/bulk" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"tag:leech","action":{"type":"suspend"},"dry_run":true}' | jq
```
Expected: `matched`, `changed`, `skipped`, `ops_emitted` (0 with `dry_run`). Run again with `"dry_run":false`
after checking the count. Cap is 5000 cards; `"card_ids":[...]` can replace `query`. Bury uses
`{"type":"bury","until":"<instant>"}`.

## 5. Card search syntax

Used by `query` in `GET /cards` and `POST /cards/bulk`. Keys and `is:` values are lowercase.
Quote values with spaces. Examples from `contracts/core/search-examples.tsv`:

| Want | Write |
|---|---|
| Due cards | `is:due` |
| Leeches | `is:leech` (or `tag:leech`) |
| Not suspended | `-is:suspended` |
| One deck | `deck:"Frieren"`, prefix `deck:Frieren*` |
| Lapses above 3 | `lapses>3` |
| Interval, stability, difficulty | `interval<21`, `stability>10.5`, `difficulty<=9` |
| Groups | `(tag:anime or tag:drama) is:due`, `-(is:new or is:suspended)` |
| Rated 1 in the last 7 days | `rated:7:1` |
| Kanji on the card | `kanji:績` |
| Entry or note | `entry:1358280`, `nid:<note uuid>` |
| Card kind, type, status | `kind:reading`, `type:vocab`, `status:known` |
| Flag | `flag:3` or `is:flagged` |
| Bare text | `食べる`, `"to eat"` |

Parse errors (`422 invalid_query`, with the position in `errors[0].position`): unknown keys
(`dek:x`), bad values (`is:newer`, `flag:8`, `rated:0`), `is:DUE` (uppercase), `entry:0123`,
unclosed `(` or `"`, a dangling `or`, and a bare `=` (quote it: `"a=b"`). `OR` and `and` are
keywords only in lowercase: `Or` is text.

## 6. Decks

### List decks
`listDecks` · scope `read` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/decks?type=user" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '.items[] | {id, name, priority, enabled}'
```
Expected: one line per user deck. `type` is `user`, `media`, `list` or `smart`.

### Create a user deck
`createDeck` · scope `write` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/decks" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"user","name":"Anime vocab"}' | jq '{id, name, type}'
```
Expected: `201` with the new deck (starts empty).

### Enable a JLPT or frequency list deck
`createDeck` · scope `write` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/deck-lists" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '.items[] | {list_key, title, item_count, deck_id}'
curl -sS -X POST "$KOTOBAYA_URL/api/v1/decks" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"list","list_key":"jlpt-n5"}' | jq '{id, name, list_key}'
```
Expected: `201` with the deck; an already enabled list returns `200` with the existing deck. The
items are built by a background `deck-build` job; `GET /deck-lists` shows the key once `deck_id` is set.

### Set a deck's priority
`updateDeck` · scope `write` · CLI: none
```sh
curl -sS -X PATCH "$KOTOBAYA_URL/api/v1/decks/$DECK" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' -d '{"priority":10}' | jq '{id, priority}'
```
Expected: `200`. New cards come from enabled decks in ascending `priority`; lower is drawn first.
Other registers in the same body: `name`, `enabled`, `new_cards_per_day`, `reviews_per_day`.

### Add items to a user deck
`addDeckItems` · scope `write` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/decks/$DECK/items" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"entry_seq":1358280},{"note_id":"'"$NOTE"'"}]}' | jq '.items | length'
```
Expected: `200` with the added items. Each element names exactly one of `entry_seq` or `note_id`.
Only `user` decks accept items (`409 deck_read_only` otherwise).

### Remove an item from a user deck
`removeDeckItem` · scope `write` · CLI: none
```sh
curl -sS -X DELETE "$KOTOBAYA_URL/api/v1/decks/$DECK/items/e:1358280" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN"
```
Expected: `204`. The key is `e:<seq>` for entries or `n:<note id>` for notes. Removing an absent item is also `204`.

## 7. Dictionary

### Search the dictionary
`searchDictionary` · scope `read` · CLI: none
```sh
curl -sS -G "$KOTOBAYA_URL/api/v1/dict/search" -H "Authorization: Bearer $KOTOBAYA_TOKEN" \
  --data-urlencode 'q=食べる' --data-urlencode 'common_only=true' |
  jq '.items[] | {seq, headword, headword_reading, glosses: .glosses[:2]}'
```
Expected: entries with `seq`. Latin input is tried as romaji and as English gloss text.

### One entry
`getDictEntry` · scope `read` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/dict/entries/1358280" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '{seq, retired, status}'
```
Expected: `200` with frequency, pitch, kanji and your status. Unknown `seq` is `404`.

### One kanji
`getKanji` · scope `read` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/kanji/$(printf %s 績 | jq -sRr @uri)" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '{literal, meanings, on_readings, kun_readings, jlpt}'
```
Expected: `200`. A character not in the kanji data is `404`; more than one character is `422`.

## 8. Settings

### Read settings
`listSettings` · scope `read` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/settings" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq -r '.items[] | "\(.key)=\(.value)"'
```
Expected: `key=value` lines for every stored setting. Unset keys use the core defaults and are absent.

### Set or reset a setting
`updateSettings` · scope `write` · CLI: none
```sh
curl -sS -X PATCH "$KOTOBAYA_URL/api/v1/settings" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"values":{"scheduler.mined_priority":5}}' | jq '.items | length'
```
Expected: `200` with all settings after the change. Send `null` as the value to reset a key to its default.
Keys are dotted lowercase (`scheduler.mined_priority`, `scheduler.new_per_day`).

## 9. Imports, media and jobs

### Import a subtitle or text file as media
`importMedia` (plus `putBlob`) · scopes `import` and `write` · CLI: `kotobaya media import FILE [--title T] [--kind anime] [--no-deck] [--new-per-day N]`
```sh
H=$(sha256sum episode01.srt | cut -d' ' -f1)
curl -sS -X PUT "$KOTOBAYA_URL/api/v1/blobs/$H?kind=upload" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/octet-stream' --data-binary @episode01.srt
curl -sS -X POST "$KOTOBAYA_URL/api/v1/media/import" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"source\":\"file\",\"title\":\"Episode 1\",\"kind\":\"anime\",\"files\":[{\"blob_hash\":\"$H\",\"filename\":\"episode01.srt\"}]}" | jq '{id, kind, status}'
```
Expected: `202` with a queued job. Follow it with `kotobaya jobs watch <job id>`.

### Import a jpdb media deck
`importJpdbDeck` · scope `import` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/media/import/jpdb" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d '{"jpdb_id":"anime/5370","title":"Frieren","kind":"anime"}' | jq '{id, status}'
```
Expected: `202` with a job. Unmapped vocabulary is listed in the job `result`, not dropped. An existing
media with the same `jpdb_id` is updated.

### Import jpdb review history
`importJpdbHistory` (plus `putBlob`) · scope `import` · CLI: none
```sh
H=$(sha256sum jpdb-export.json | cut -d' ' -f1)
curl -sS -X PUT "$KOTOBAYA_URL/api/v1/blobs/$H?kind=upload" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/octet-stream' --data-binary @jpdb-export.json >/dev/null
curl -sS -X POST "$KOTOBAYA_URL/api/v1/import/jpdb/history" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"blob_hash\":\"$H\",\"import_reviews\":true,\"import_word_status\":true}" | jq '{id, status}'
```
Expected: `202` with a job. Re-running imports nothing twice; mapping counts appear in the job `result`.

### List jobs and check one
`listJobs` / `getJob` · scope `read` · CLI: `kotobaya jobs list [--json]`, `kotobaya jobs watch JOB_ID [--interval S]`
```sh
curl -sS "$KOTOBAYA_URL/api/v1/jobs?status=running" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '.items[] | {id, kind, progress_message}'
curl -sS "$KOTOBAYA_URL/api/v1/jobs/$JOB" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '{status, progress, error, result}'
```
Expected: `status` is `queued`, `running`, `succeeded`, `failed` or `cancelled`. `result` is null until the job finishes.

### Cancel a job
`cancelJob` · scope `import` · CLI: none
```sh
curl -sS -X POST "$KOTOBAYA_URL/api/v1/jobs/$JOB/cancel" -H "Authorization: Bearer $KOTOBAYA_TOKEN"
```
Expected: the job is `cancelled` if it was `queued` or `running`.

## 10. Admin

### Refresh the dictionary
`refreshDictionary` · scope `import` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/dict/release" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '.freq_lists[].id'
curl -sS -X POST "$KOTOBAYA_URL/api/v1/dict/refresh" \
  -H "Authorization: Bearer $KOTOBAYA_TOKEN" -H 'Content-Type: application/json' -d '{"freq_lists":["jpdb","bccwj"]}' | jq '{id, kind, status}'
```
Expected: `202` with a `dict-refresh` job (`200` if one is already queued or running). Use ids printed by the
first command; the body may also be `{}`.

### Run the FSRS optimizer for a preset
`optimizePreset` · scope `import` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/presets" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '.items[] | {id, name, optimized_at}'
curl -sS -X POST "$KOTOBAYA_URL/api/v1/presets/$PRESET/optimize" -H "Authorization: Bearer $KOTOBAYA_TOKEN" | jq '{id, kind, status}'
```
Expected: a `fsrs-optimize` job. The worker publishes new weights only when they are measurably better.

### Health
`getHealth` · public, no token · CLI: `kotobaya health`
```sh
curl -sS "$KOTOBAYA_URL/api/v1/health"
```
Expected: `{"status":"ok","version":"…","time":"…"}`. `503` with problem `unavailable` when Postgres is unreachable.

## 11. Sync, devices and tokens

### Pull sync ops
`syncPull` · scope `read` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/sync/pull?since=0&limit=100" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '{count: (.ops | length), has_more, next_since}'
```
Expected: ops after `since`. Loop with `next_since` while `has_more` is true.

### List devices
`listDevices` · scope `admin` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/devices" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '.items[] | {id, name, kind, last_seen_at, revoked_at}'
```
Expected: every device, revoked ones included.

### Revoke a device
`revokeDevice` · scope `admin` · CLI: none
```sh
curl -sS -X DELETE "$KOTOBAYA_URL/api/v1/devices/$DEVICE" -H "Authorization: Bearer $KOTOBAYA_TOKEN"
```
Expected: `204`; all tokens bound to the device are revoked. Ops it has not pushed are lost. The server device cannot be revoked (`409`).

### List tokens
`listTokens` · scope `admin` · CLI: none
```sh
curl -sS "$KOTOBAYA_URL/api/v1/tokens?active=true" -H "Authorization: Bearer $KOTOBAYA_TOKEN" |
  jq '.items[] | {id, name, kind, token_prefix, scopes, last_used_at}'
```
Expected: active credentials only (`kind` is `session`, `device` or `personal`). Drop `?active=true` for all rows.

### Revoke a token
`revokeToken` · scope `admin` · CLI: none
```sh
curl -sS -X DELETE "$KOTOBAYA_URL/api/v1/tokens/$TOKEN_ID" -H "Authorization: Bearer $KOTOBAYA_TOKEN"
```
Expected: `204`. Idempotent. Revoking the token you are using works, and the next call returns `401`.
