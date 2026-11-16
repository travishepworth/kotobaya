# REVIEW-UNLIMITED — Opt-in "no review limit" toggle (ADR-034)

- Phase: P10 · Model: opus (architect for contracts, then writer-senior) · Risk: med-high (queue builder)
- Deps: RC-SERVER, RC-AND, RC-WEB merged (owned-path overlap)
- Branch: t/REVIEW-UNLIMITED
- Owned paths: contracts/ (docs: db/SCHEMA.md §6 settings, api openapi settings description, core SUMMARY/KDoc), core/ (QueueBuilder rule 3 / QueueLimits), server/ (SettingsApi WELL_KNOWN + validation, ReviewSettings, QueueLoader), android/ (StudySettingsModel, LocalQueueLoader, settings UI + i18n), web/ (settings UI + i18n)
- Module(s) for CI: contracts, core, server, android, web

## Read
- docs/DECISIONS.md ADR-034, ADR-033, ADR-031
- Scout notes: cap is `scheduler.reviews_per_day` default 200 (core QueueBuilder.kt:169, server ReviewSettings.kt:24, SCHEMA.md:561), applied in QueueBuilder rule 3; per-deck `deck.reviews_per_day`; wired in server QueueLoader.kt:108 and android LocalQueueLoader.kt:199; Android UI StudySettingsModel.kt:15; server validation SettingsApi.kt:38.

## Do
1. Contracts (docs/additive only): document `scheduler.reviews_unlimited` bool default false.
2. Core: QueueLimits gains `reviewsUnlimited` (or nullable cap); rule 3 skips global + per-deck review caps when set. Keep going behaviour unchanged.
3. Server + Android: read the setting into the build; settings validation accepts bool.
4. UI: Android and web study settings get a toggle "No daily review limit" (i18n), which disables/greys the reviews-per-day field.

## Acceptance
- [ ] core test: 300 due reviews, cap 200: off → 200 served + counts.review 200; on → 300 + counts 300.
- [ ] core test: per-deck cap ignored when on; new-card limit still applied.
- [ ] server test: setting round-trips via settings.set and changes /review/queue counts.
- [ ] make check for each touched module green.
