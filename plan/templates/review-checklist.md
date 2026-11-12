# Review checklist

## Every ticket
- [ ] Data-safety tests from CLAUDE.md exist for anything this ticket touches.
- [ ] Only owned paths changed (plus HANDOFF.md, lockfiles).
- [ ] Matches contracts exactly: names, types, nullability, error shapes, scopes.
- [ ] No hand-edited generated code; no secrets; no `fixtures/private/` content committed.
- [ ] New dependencies justified in HANDOFF.md.

## Data and sync (any ticket touching learning data)
- [ ] All mutations go through sync ops; op payloads validate against `contracts/sync`.
- [ ] Idempotent: applying the same op twice is a no-op.
- [ ] Out-of-order and duplicate ops handled; HLC used for ordering, never wall-clock alone.
- [ ] Drill/cram reviews never change FSRS state.
- [ ] Nothing deletes reviews. Deletes are tombstones.

## Time
- [ ] Stored as UTC instants; day boundaries use the user's time zone and the configured rollover hour.
- [ ] Tests cover a time-zone change and a DST transition where relevant.

## Auth and API
- [ ] Every new route declares and enforces its scope.
- [ ] Errors are problem+json; no stack traces or internals leak.
- [ ] Pagination is cursor-based where lists can grow.

## Android
- [ ] Works fully offline (no network calls on the review/add/widget paths).
- [ ] No work on the main thread; WorkManager/alarms batched as specified.
- [ ] Strings via resources; content descriptions for icons.

## Web
- [ ] Uses the generated TS client only; keyboard-accessible; strings via i18n.

## Worker
- [ ] Jobs are resumable and idempotent; progress reported; network calls rate-limited and cached.
