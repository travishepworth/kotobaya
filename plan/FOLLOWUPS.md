# FOLLOWUPS
<!-- Minor review findings parked here. The orchestrator turns clusters of them into tickets at each phase gate. -->
- T207 (jpdb import): jpdb vid == JMdict ent_seq; 260 vids repeat with different spellings — merge by seq. API endpoint/field names unverified (no key) — see spikes/jpdb-map/REPORT.md.
- Compose SeaweedFS has no S3 auth config (dev only); app must create S3_BUCKET on startup.
- Env var contract for api/worker (`DATABASE_URL` JDBC form, `S3_*`) was guessed by T009; P1 server/worker tickets must read exactly these names or update chart + compose together.
- Helm migrate hook is a `true` placeholder; P1 migrations ticket replaces it.
- T207: importer must still tolerate `from_anki: true` records (none in the real export, so the fixture omits them).
- Release APK is unsigned (no signingConfig); add signing via env vars in a later android ticket.
- Makefile `check MODULE=contracts` is a stub: wire to contracts/db/check.sh (+ later contract checks). Fold into T110 or a small ticket after T004–T006.
- V1 migration aborts on LC_CTYPE=C databases (pg_trgm ignores Japanese). Helm postgres (T111) and any external Postgres must init with a UTF-8 ctype (C.UTF-8); add to HUMAN_CHECKS if using an existing cluster DB.
- T003 (doc, contracts/db): concurrent re-match is arrival-order dependent (A merges W→X; B's newer entry_seq wins the register but W stays merged). Replicas converge; document as trade-off.
- T003 (doc): delete racing a duplicate — arrival order decides whether L stays live. Converges; document.
- T003 → T205/readers: deck_item `n:<loser id>` must follow merged_into_id (SCHEMA.md should say so, as for confusables).
- T003 → worker parse ticket: detected grammar span PK excludes rule_index; dedupe and keep lowest rule_index.
- T003 (doc): a duplicate's filled fields are copied to the survivor even if the duplicate was deleted before push. Mention in SUMMARY.md.
- T003 → core/android apply: append-only for review_log/change is enforced by Postgres triggers only; device apply code needs a test.
- T003 architect followups (two-level merge convergence, loser deleted_at, rejected-op rollback, etc.) moved into plan/tickets/T005.md § Must also resolve.
- Makefile `check MODULE=contracts` must run contracts/db/check.sh + contracts/sync/check.sh (+ api check from T004).
- T005 → T004: validate push bodies with `PushRequestLenient`; per-op errors in `PushResult`. Snapshot blob formats undefined (list/media deck snapshots) — needs an owner ticket.
- T004: undefined — snapshot archive formats (also flagged by T005), `confusable.suggested` trigger, pairing-code storage (in-memory). Web reviews attributed to the server device, not per browser.
- T005 → contracts/db (C-ticket or T006 batch): SCHEMA.md §5.3 purge wording (acked rows kept until durable_seq) + 5 new sync_state keys (incl. remint_offset).
- T005: after a server restore, REST- and job-originated ops since the last backup are lost (device ops are re-pushed). Backups ticket (T112) should note it; HUMAN_CHECKS on backup frequency.
- T004: job retention unspecified; importMedia.import_id idempotency depends on job/media rows surviving — define retention in the jobs ticket (T109).
- T004: POST /cards/bulk/schedule needs only `review` (a review-only token can reset 5000 cards) — consider requiring review+write.
- T004 → T105 (auth): `x-auth: body-key` / `x-body-key-scope` are custom extensions; the auth filter must implement them by hand.
- T004: /cards/bulk/schedule retry is not idempotent (extra manual rows per card; same schedule). Consider an optional request id.
- T005 → T006/T107 acceptance: reference scenarios `clock-behind-remint` and the new clock_ahead re-mint scenario.
- T005 (doc/contract minor): replay by reviewed_at can put a causally later review first when one device's clock is ahead within tolerance. Same on all replicas; consider max(reviewed_at, newest observed review wall) or document.
- T005: re-mint misses `deck.item_put` (`e:<seq>` into an existing deck) and `word.status_set` for words created in the window → discarded; count `e:` keys as dependent on W notes holding that entry_seq.
- T005 (doc): `card.preset_set` on an existing card pointing at a preset created in the window is re-minted (contradicts "edits of existing rows discard-only"); clarify text.
- T005: pin op-size measurement to raw UTF-8 compact JSON (no \uXXXX escaping beyond JSON's requirements).
- T005 → T303 (sync client): store the last /meta limits durably and check max_op_bytes before minting (a server below the 1 MiB default would otherwise force Discard of offline notes).
- T004: idempotent-retry responses carry no `Kotobaya-Seq` (no op emitted); consider returning the original op's seq.
- T004: `Kotobaya-Seq` is only meaningful within one log_epoch; include the epoch in the header or document "ignore after log reset".
- T004 → T303: pull page bound by max_push_bytes is only a SHOULD; clients must accept pages up to max_push_bytes + max_op_bytes.
- Makefile `check MODULE=contracts`: wire contracts/db/check.sh + contracts/sync/check.sh + contracts/api/check.sh (plus contracts/core once T006 lands).
- T006 → T213: assets/card/card.css must define CardClasses.ALL.
- T006/T005 drift: SCHEMA.md §4.2 replay order (hlc,id) vs SYNC.md §9.1 (reviewed_at,hlc,id); add sync_state key `core_scheduler_version` to SYNC.md §10.5. Fix in a C-ticket at the freeze.
- Harness: the Write tool refuses SUMMARY.md for subagents. Future contract tickets: name it DESIGN-NOTES.md, or the orchestrator commits it.
- C-1: V1 edited in place (server_state table, replay index) — any existing dev Postgres volume needs `make dev-down` + volume wipe (Flyway checksum). Fine pre-deploy.
- C-1 → T104/T111: put the SYNC 10.5 startup replay behind the readiness probe.
- C-1 → T303/T107: the device's schema check for pulled ops (SYNC 6.1) needs a JSON Schema 2020-12 validator (prefixItems, items:false); declare the dependency in HANDOFF.
- T006 → T107: verify py-fsrs 6.3.2 uses only `**` / `math.e ** x` (no math.exp) and mirror exactly; test paramsOf fallback values equal py-fsrs defaults.
- T006 → T104/server push: an op with a duplicate JSON key makes CanonicalJson.decode reject the whole body (400 for the entire outbox). Decide: per-op `schema_invalid` via a lenient per-op parse. Raise in the freeze review.
- T006/ADR-006: round-robin within a priority tier — confirm the deck.priority default and that it matches SPEC "priority order" (HUMAN_CHECKS).
- T006 → T107: jvm/js digest must include fdlibm special-case and subnormal arguments (unsigned-compare branches of e_pow), not only FSRS ranges; build PortableMath constants with Double.fromBits from fdlibm hex words.
- Planner: Helm migrate hook is pre-upgrade only, so a first `helm install` never migrates. T111 must cover install too (pre-install,pre-upgrade).
- Planner: health routes differ (compose/chart probe /healthz, backlog /healthz /readyz /metrics, openapi /health). T104 serves all; T111 aligns probes (readiness = /readyz incl. the SYNC 10.5 startup replay).
- Planner: worker package path is worker/src/kotobaya_worker/ (backlog said worker/kotobaya_worker/); tickets corrected.
- T101: KRAD tested only on a synthetic zip (no KRAD fixture); EDRDG URLs unverified offline; `ref.kanji.radical` = KANJIDIC2 classical radical number as text (contract silent). First real run → HUMAN_CHECKS.
- T108a: SearchExamples.kt is a snapshot of search-examples.tsv (commonTest can't read files) — regenerate when the tsv changes (C-2 may add examples). JS tests for search run once T107a wires :core:jsTest.
- T108a: CoreJsImpl.renderCard/previewIntervals throw NotImplementedError until T213/T107b.
- T102: Tatoeba→entry linking is substring-based (over-links). Re-link with the parser once the parse ticket lands (Phase 4 parser). No default frequency lists or Tatoeba URL: sources are config (ExtraSources) — pick lists in DECISIONS (HUMAN_CHECKS).
- Freeze A → T202/T104: server's persisted HLC `last` location is "T-server chooses"; use a server_state key `hlc_last` (restore finalization raises it in the same tx).
- Freeze A → T202 tests: raw-push `same-op-id-equal-contents` — assert the second element gets `duplicate`; jpdb-import kanji dedupe vs a concurrent offline phone add goes through SYNC 8 merge (test it).
- Freeze B → T207: RESOLVED by ADR-009 (human answers). Needs a small C-ticket on openapi JobResultJpdbHistory (one card per vocab note; suspended writing cards; known-only handling) before T207 starts.
- Freeze B → T112: order the object-storage mirror against pg_dump (mirror after dump, or include in "verified").
- Freeze B → T202/T111/T112: name the "backups disabled" server setting and wire it in Helm, else durable_seq stays 0 and devices never purge.
- T107b minors (next core touch): fix the round(-0.5) comment (Python returns int 0); mark the fuzz lastPositive fallback unreachable; remove unused kotlin.math.floor import; add a repeated-AGAIN test that hits the difficulty clamp at 10 (vectors never reach it, nor STABILITY_MIN); FsrsVectorsDriftTest should also compare vectors.json `scheduler` block with vectorParams/defaultWeights. KotobayaCore `instant()` rejects pre-1970 ms.
- C-3 doc: DICT_PACKAGE 3.2 envelope example lacks `table_digests`; 3.4 step 1 should check `table_digests` has exactly the 11 tables (T103b implements it anyway). Next C-ticket.
- C-3 → T302: digest pass over the `all` scope runs inside the write transaction (millions of gloss rows) — run off the main thread; expect seconds.
- T108b API (kotobaya.search.sql, KDoc is the de-facto contract): `SearchSqlCompiler.compile(node, Dialect, SearchContext): CompiledSearch`; callers on SQLite with `residual` MUST select via `select()` and keep only rows where `accepts` is true; `LIMIT` only when `!residual`; bulk actions (T204/T210, server bulk ops) MUST apply `accepts` before acting; `SearchContext.dayStart` must be tz-aware (platform computes). Add to contracts/core README in the next C-ticket.
- T103b → T109b/jobs: the dictionary refresh job must store each release's `artifacts` in ref.dict_release (build_release doesn't write them) and pass S3 so diffs can be built from the published base; without S3 it is full-only.
- T110: client generation normalises a temp copy of the spec (drops string param defaults, if/then/else allOf, `false` property schemas; const→enum). Clients are looser than the contract; the server validates. Python client: 1 generator-side mypy error, only compileall+import gated. `make check MODULE=contracts` regenerates clients and fails on drift — any contract change must regenerate clients in the same branch.
- T104 → T111: compose `api` needs DATABASE_URL, MIGRATE_ON_START, and Postgres POSTGRES_INITDB_ARGS=--locale=C.UTF-8 (T111 owns compose locale/healthcheck changes — extend to these env vars).
- T104: jOOQ used as plain-SQL DSL (no codegen yet; ADR-007 says generated — add codegen when T202 needs it). Extra error codes `not_implemented` (501) and `method_not_allowed` (405) not in the API error table → next C-ticket. JVM zone forced to UTC.
- T104 → T203: replace PLACEHOLDER_SCHEDULER_VERSION="unset" with core ReplayEngine.schedulerVersion and wire SchedulerReplay (called in the SYNC 10.5 tx). T202 fills server_state `hlc_last`.
- WIRING (after T105a, T106, T109a merge): register dictHandlers(db) (+ auth/jobs handlers) in AppDeps in server Main.kt — T106 handlers exist but are unreachable until wired. One small ticket W-1 owning Main.kt only.
- T106: romaji→hiragana converter is local to server dict/ — move to core if the device (T302) needs parity. /dict/entries/{seq}/examples stays 501 until example sentences are populated (T102 data + media). note_id overlay untested.
- T105a → W-1 + T111: wire Auth(db, AuthConfig.fromEnv()) into AppDeps; Helm/compose must pass AUTH_PASSWORD_HASH (required) and AUTH_TOTP_SECRET (optional) from a Secret. Setup command: java -cp server-all.jar kotobaya.auth.HashPasswordKt (→ HUMAN_CHECKS at deploy).
- T105a → W-1/T111: Traefik externalTrafficPolicy: Local; set AUTH_TRUSTED_PROXIES to the ingress/pod CIDR so login rate limiting is per real client.
- T109a → W-1: wire `val jobs = JobsModule(db, config.databaseUrl)`, `AppDeps(handlers = jobs.handlers)`, `jobs.start(scope)`.
- T109a → T109b: worker heartbeats/progress writes must be guarded by `status='running'` (cancel sets cancelled immediately). job.updated comes from a 250 ms poll of app.job; NOTIFY is only a hint. Retention: terminal jobs pruned after 90 d; media-import / jpdb-* kept forever.
- T109a → T105b: call `hub.closeToken(id)` on revocation and `bus.publish` for device.* events (credentialValid hook exists).
- T109a → T309: ntfy forwarding needs a sink on EventHub + a base-URL setting.
- T109b: worker registry is empty; health/readiness and Dockerfile CMD untouched → W-1.
- T105a hardening (next auth touch, e.g. T105b or T609): parse XFF hops only from the right up to the first untrusted hop (ignore malformed hops further left); key IPv6 clients on /64; strict IP-literal parsing (no getByName DNS fallback).
- W-1: dict-refresh end-to-end (network + S3) untested; needs DICT_SOURCES_JSON (format in worker/src/kotobaya_worker/jobs/dict_refresh.py), S3_* on the worker. Packaging runs after load, so ref.dict_release.is_current flips before artifacts exist — GET /dict/release should only advertise a release whose artifacts are present (server dict/ or a guard in the job: package first, flip is_current last).
- T111/e2e: k3d dev serves plain HTTP on :8099, but the session cookie is `Secure` (contract), so browser/curl logins don't persist in dev. Fix before T208 (web shell): k3d serves HTTPS via Traefik's default self-signed cert (map 8443→443) and up.sh prints the https URL. Verified in-cluster 2026-11-12: /api/v1/health ok, unauthenticated search 401, login 200; authenticated search covered by W-1 WiringTest.
- T111: compose initdb locale only applies to a fresh pgdata volume; DICT_SOURCES_JSON placeholder `{}` in up.sh; no "backups disabled" server setting yet (T112 may use api.env). Set PUBLIC_URL in deploy (T105b pairing URI).
- T105b → next C-ticket: pairDevice returns 422 /kind when re-pairing a `script` device id with another kind (add to openapi edge cases); bulkScheduleCards scope review vs review+write (CCR candidate).
- T105b → T609: parse X-Forwarded-Proto/Host from the right like XFF.
- T112 → T202: server must read BACKUPS_DISABLED=true and report durable_seq = high_water_seq (else 0 → devices never purge acked rows).
- T201a → T213a/T201b: CardGenerator (contract signature has no note fields) doesn't skip a card kind whose front template renders empty; the caller (T201b note create, using T213a rendering) must apply the 'skip empty front' rule.
- C-4 → C-ticket later: `JpdbHistoryImportRequest.import_word_status` description still mentions never-forget/blacklist; align with ADR-009 (doc-only).
- T208 → any web ticket: eslint.config.js doesn't parse `*.svelte.ts` and flags `no-navigation-without-resolve`; lint isn't a gate, fix opportunistically (owner: whoever next owns web config).
- T208 → web tickets T209–T214: SvelteKit 3 has no `$lib` alias; use relative imports, generated client via `web/src/lib/api/generated.ts`; adapter config is in vite.config.ts (no svelte.config.js).
- T212 -> C-ticket/T202c: `/meta` lacks backup fields (last verified backup, durable_seq, BACKUPS_DISABLED); settings UI shows 'not reported'.
- T212 -> T212b: settings keys `scheduler.kanji_before_vocab`, `scheduler.mined_priority` not in SCHEMA §6; confirm/add via contract (additive).
- T212: own `web/src/lib/settings/messages.ts` instead of shared i18n.ts; fold into shared i18n later.
- T212: token scope text says `write` covers bulk scheduling (pending bulkScheduleCards scope CCR).
- T213a: CardCssDriftTest lives in core/src/jvmTest/…/render/ (outside owned paths; accepted, same pattern as BuiltInNoteTypesDriftTest).
- T213a: {{pitch:}}/{{cloze:}} work only in built-in templates; frontIsEmpty ignores sentence/image extras; cloze hint = ClozeSentence.translation.
- T207a -> T207b: register handler in worker runner/__main__.py: reg.register("jpdb-history-import", make_jpdb_history_import(DATABASE_URL, fetch_blob, apply_plan)); PostgresRefLookup has no DB test; ImportPlan JSON schema v1 in plan.py docstring.
- T207a review -> contract (next review-id version): vocab id key lacks entry_seq; a vid text-mapped to two seqs with identical (t, grade, occ) collides → 2nd counted already-imported, review lost silently (S02: ~never on real data). Add entry_seq or a collision bucket.
- T207a review -> contract: occ counts same-timestamp events per group; a newly-mapped row after a JMdict update can shift occ → reimport. Note in id rule.
- T207a review -> C-ticket: openapi 'malformed' should state timestamp range 0..2^48-1 ms (writer counts out-of-range as reviews_unknown_grade).
- T207a review -> T207b/Phase 4: carry Mapped.candidates into VocabRow/plan JSON so ambiguous text matches are visible.
- T207a review -> T207b: Testcontainers parity test PostgresRefLookup vs in-memory.
- T207a review: B_MALFORMED == B_UNKNOWN_GRADE string coincidence in plan.py:74; use one constant.
- T213b: web/tests/core.test.ts (pre-existing, outside owned paths) imports @kotobaya/core directly; move to web/tests/core/ or delete. App code must import only web/src/lib/core/index.ts.
- T213b -> T209/T210b: no app module imports the core wrapper yet, so pnpm build doesn't bundle core; first UI consumer must verify the production build.
- T202a review (minor): no jvm-vs-js pattern parity table existed; added in attempt 3. Worker (Python) and web must also use ECMA pattern semantics once C-5 lands.
- T202a -> T202b: handle new PushElement.TooDeep exactly like DuplicateKey (per-op schema_invalid).
- C-5 review -> next contract ticket: SYNC.md:110 says patterns run without `u` flag but :124 says code points; say "per code point (≡ u flag)", require new patterns valid with and without u.
- C-5 review -> contract tools: validate.py ecma_to_python silently mistranslates `[\s-z]` and `a{,5}`; raise on them (no v1 pattern uses them).
- C-5 review -> T201b/T207b/T204/T210a (REST + importers): SYNC 2.1 doesn't say what REST/import does with input that would mint a pattern-failing op (tag with U+3000/NBSP). Default: REST → 422 invalid_value; importers split tags on ECMA \s and count dropped tags. Confirm in next contract ticket.
- C-5 review -> contract: SYNC 2.1 "MAY reject" deeper-than-512 docs; add "(push parse MUST, 5.2)".
- T203 (T005 carry-over): replay orders by reviewed_at, so a clock-ahead device (within tolerance) can order a causally-later review first; replicas still converge. Revisit if users see odd intervals after offline multi-device study.
- T203 review: HistoryGen never delivers a row already undone (undoneHlc at insert, as a rebuild/pull page would); add to property test.
- T203 review: HistoryGen has ≤1 params change per history; allow 0–2 (lazy bump then eager same-version change before next fold).
- T203 review: ReplayGoldenTest compares S/D with 1e-6 tolerance (bit identity covered by jvm/js digest test); note it.
- T203 review: IncrementalReplay.undo must only be called when UndoOutcome.replayTarget; document in KDoc.
- T203 architect: property test calls table.read() after every event, so lazy Params is resolved immediately → decide rule 2 / stale-fold guard only unit-tested. Compare against non-mutating view.
- T203 architect -> next C-ticket: add @throws to ReplayEngine.replay KDoc; reword SYNC §9 (SYNC.md:946) — SET_DUE on NEW card leaves it unchanged (Scheduler.kt:317), T204/T205 UI must not expect a due date.
- T203 architect: ReplayGoldenTest digest failure message should say "bump SCHEDULER_VERSION" (constant lives in replay/, separate from FsrsScheduler).
- T203 architect -> next C-ticket or Android: IncrementalReplay/replaySet/rowOrder/SCHEDULER_VERSION public but not frozen.
- T202b -> wiring (W-2, after T202c): Main.kt must add `SyncModule(db, SyncConfig(events = { d, hw, _ -> jobs.bus.syncChangedInTx(d, hw) }))` and register its handlers; sync endpoints are not live until then.
- T202b -> next C-ticket: payload U+0000 or int > int4 range → invalid_value (Postgres can't store); contract silent.
- T202b: Kotobaya-Seq absent on idempotent retry (T004 follow-up resolved as 'document'); no jOOQ codegen (to_jsonb/jsonb_populate_record).
- T202b review -> T207b: OPS.md media.created requires a server-emitted deck.created (caused_by_op_id) right after; OpApplier.mediaCreated returns no FollowUp (mediaDeleted does). Add FollowUp or emit in T207b.
- T202b review -> next C-ticket: add schema `maximum` (int4) for new_cards_per_day/reviews_per_day/steps and a no-U+0000 rule so clients check before minting.
- T202b review -> T201b/T204/T206/T210a: Kotobaya-Seq header absent on idempotent retry — handlers must not assume it.
- T202b review: ADR-007 deviation — sync uses to_jsonb/jsonb_populate_record, no jOOQ codegen. Record in DECISIONS when next touching ADR-007.
- T202b review: AppendOnlyGuard misses CTE `with … delete` (DB trigger still blocks); match anywhere.
- T202b review: `!!` in main code (PushService.kt:99, OpApplier) → requireNotNull/checkNotNull.
- T202b verify: SyncJson.kt:67 iso() uses default-locale String.format → use Locale.ROOT (non-Latin digits under e.g. ar-EG).
- T202b verify: OpApplier.kt:523 media deck.created silently skipped if a user deck already has the media id (deliberate UUID reuse only).
- T201b -> wiring (W-2): Main.kt `handlers += NotesModule(db, sync.opApply).handlers` (same OpApply as SyncModule).
- T201b -> T210a: per-note card additions for optional kinds go through createCard (ADR-011).
- T204 -> wiring (W-2): Main.kt `handlers += DecksModule(db, sync.opApply).handlers`.
- T204: JLPT list decks report item_count 0 (no vocab source in DB yet); getDeckCoverage stays 501 until Phase 4. decks reuse internal helpers from kotobaya.notes (Errors, Reply) — consider a shared http-support package.
- T211 -> T209: note-types/CardView.svelte is a stand-in; swap to T209's CardView (one import in Editor.svelte).
- T211: core renderer gives no warning for unknown placeholder {{nope}} (renders empty) → core render warnings follow-up. No nav link to /note-types (layout); deck settings (order, add_kanji, card_kinds) not editable in UI.
- T202c: ScenarioTest.mergeScenariosAreLeftToT202c (T202b file) now stale; remove in wiring ticket.
- T202c -> wiring (W-2): Main.kt uses SyncWiring.startup(db) and SyncModule(db, SyncWiring.config(events)); replaces PLACEHOLDER_SCHEDULER_VERSION.
- T202c review: CoreCardCache refusals only logged; no durable marker so card never retried (no known trigger). Write sentinel weights_version on refusal → next touch full replay.
- T202c review -> wiring (W-2): Startup.kt StartupSequence default "unset" scheduler version; drop default / use core SCHEDULER_VERSION so forgetting SyncWiring.startup can't silently skip replay.
- T202c review: DuplicateMerge.kt:107 survivor-card lookup add `and merged_into_id is null` guard.
- T202c review: fuzz covers only Default preset; add preset update/publish, card.preset_set, restore/re-mint actions.
- T202c review -> Phase 3 (device re-mint): T005 re-mint gaps (deck.item_put e:<seq>, word.status_set in window, card.preset_set) untested.
- C-6 verify: wording "≤ about 10102" for derived due_at; ReplayEngine @throws KDoc change not in HANDOFF; T202d/web: check TS/JS readers parse 5-digit unsigned years (10099-…).
- C-7 verify -> T212b/server loaders: convert jsonb settings numbers to binary64 before the integer test; never test BigDecimal scale (1.0000000000000001 must be valid = 1). Also guard Infinity (1e309) with the range check.
- T202c architect: fuzz oracle RefReplica narrower than model.py (fsrs_params.deleted ignores clock rule; cacheOf uses DEFAULT_PARAMS; note.created skips grammar_point check) — make projection() fail on non-Default preset or out-of-subset op types.
- T202c: implement C-8's engine-refusal behaviour (stale marker) in CoreCardCache once C-8 merges (server follow-up ticket).
- T212b -> C-8: SCHEMA 6.1 says valid settings values are 'stored as sent', but OpApply canonical JSON stores 1.0 as 1 (same value, valid). Reword to 'stored in canonical JSON form'.
- T212b -> wiring (W-2): Main.kt `handlers += SettingsModule(db, sync.opApply).handlers`.
- T202d review: OpApply.validateOwn calls encodeTree first, so REST NUL 422 loses its field pointer; call nulPointer first. SyncJson.containsNul (recursive) + OpRules.kt:93 branch now dead on push path; delete/replace with nulPointer.
- W-2: device pairing codes are in-memory (single API replica only); persist if the API ever scales out.
- W-2 -> T206: add `handlers += ReviewModule(db, sync.opApply).handlers` in Main.kt composeApp (W-2 is merged; T206 or a follow-up owns it).
- T206 -> T209/C-ticket: queue doesn't pass pendingEntries (deck entries without a note) to the builder because getQueue emits no ops; if web must introduce them, needs a CONTRACT_CHANGE_REQUEST (e.g. a materialise endpoint).
- T206: drill sources hot_words/confusables → 501; listCardReviews left to T210a; sentence always null (no sentence tables); newShownThisSession=0; utc_offset_min stored NULL when absent (per contract). Dict entry/kanji JSON duplicated from DictApi — dedupe.
- T209: no furigana alignment in API (whole reading as one ruby segment); sentence/cloze not wired (server null, no span data). Review screen untested in a live browser → HUMAN_CHECKS GATE-P2 walkthrough.
- T210a -> next C-ticket: bulk/schedule has no idempotency id; a retried POST appends extra manual review rows (end state same for set_due/reset, but log grows). Add request_id / derived manual-row ids.
- T210a: is:hot matches nothing (HotScorer inputs not loaded); card list cursors offset-based not keyset; bulk/schedule scope review-only (T105b review+write CCR candidate still open).
- T210b: no deck column (Card/NoteSummary API has no deck); no tag listing endpoint; preview passes no dict entry. 'All matching' needs non-empty query (contract minLength 1).
- W-3: nav active marking is exact-match; /cards/<id> etc. don't highlight parent — switch to prefix match.
- T214: generated TS client names the model `Blob`, shadowing DOM Blob (putBlob body cast `as never`) — rename in openapi/generator config. No mapping review queue route yet (Phase 4?).
- C-9 review -> next C-ticket: say that after a retry, counters reflect rows already written as notes_existing/reviews_already_imported (under-reports this job's own writes); say in cancelJob/importJpdbHistory that re-running after cancel completes the import and reports totals.
- T207c: no-resurrection rule skips a whole group and can't catch known-only kanji cards with no reviews; worker job_result collisions detect intra-plan only (server result authoritative).
- C-8 -> Phase 3 (Android): device re-mint per SYNC 10.4 (1a/1c, E ordering, n: re-key with live-owner condition, double removal, list D, MUST-show discard list); Android op-apply/rebuild replay writes refused cache; Android queue loader excludes refused cards.
- C-8 verify (minor): window-only reorder re-put of a pre-window e:s leaves old e:s row live (may keep old position); optional re-put on e:s not done.
- F-2 review -> P3 C-ticket (queue, not data): ALONGSIDE add_kanji group exists only while V is NEW; after answering V a rebuild drops its K when K's home deck newPerDay=0 (breaks 'newPerDay=0 kanji deck still feeds via add_kanji'). PROVISIONAL default: K of a word introduced today stay drawable today (charged to home, never skipped). Also confirm 'group doesn't fit → turn passes' wording; then core change + tests (build TodayReview.deckIds from memberships; mid-group partial-answer rebuild for BEFORE/ALONGSIDE).
- F-3 review: CoreCardCache forces FULL_REPLAY on refused cache before core decide (redundant on non-feeding ops; rely on step 1b); replayAll count includes refusals; add undo-heals + preset-heals tests on refused cache; fuzz REMINT_CASES add '1a-window-row' counter; upgrade note: rolling update with a pre-F-3 instance and backups disabled can briefly show durable_seq lower (harmless).
- T301b: Settings screen still a placeholder — Devices section registered but not shown; wire when Settings renders settingsSections. hlc_regressed retry mints a new device id; replica/outbox reset is T303c's job.
- T304a -> P3 follow-up: Android SmartDeckResolver seam empty (smart decks not resolved on device; wire core/T108b SQLite search compiler) and headwordOf seam empty (dictionary headword when note has no expression; needs T302). Parity test vs hand-built fixture + core builder, not literal server replay.
- Android FeatureModules.kt: every Android ticket adds one line → rebase conflicts; resolve by keeping all entries.
- T302 -> app wiring: app start should call DictWork.ensurePeriodic + installer.startup(); db.DictionaryProvider seam superseded by InstalledDictionary (remove). core/dict/Romaji.kt DUPLICATES server dict/Romaji.kt — server should depend on core's. Unverified on device: zstd-jni, HTTP Range resume → HUMAN_CHECKS.
- T308 -> T303c/T304b: callers that change reviews or sync must call get<DueTrigger>().fire(); NotifySignals seam reads sync_state.last_sync_at — swap to T303c SyncStatusRepository. Notification permission rationale route `notify_permission` exists; onboarding must navigate to it.
- T305a: make apk builds both flavors (Makefile apk line not owned); provider call-log debug screen not built (needs FeatureModules line + strings); APK rename uses internal AGP BaseVariantOutputImpl (may break on AGP upgrade).
- T303a/C-10: name `client.log_reset_epoch` in SYNC 10.5 key list (next C-ticket) so Android key set doesn't drift; transient push failures count toward the unknown_reference auto-retry cap of 10 (may end retries early on flaky network).
- T307/T309: MainActivity must handle kotobaya://review and kotobaya://word/<seq> VIEW intents; PushSyncTrigger no-op until T303c rebinds; callers fire DueTrigger; hot word = highest-lapse vocab (core HotScorer not wired); ApiClient lacks PATCH (DevicePushApi own call); sync.changed can't skip originating device.

## T303b (architect second pass, 2026-11-13)
- auto_status not re-derived on device (OPS.md says every replica does; status=coalesce(override,auto,'new') shows 'new' on device) → folded into T304b acceptance.
- DeviceSyncCycle: auto-retry cap of 10 also counts network-failure attempt bumps from pushAll; flaky network exhausts retries.
- DeviceSyncCycle: no auto-retry when rebuild returns UpgradeRequired.
- Rebuild pull after wipe always reports progress → extra retry round on same-epoch rebuild (harmless).
- Device fuzz lacks server restore and out-of-order delivery; §12.5 covered only by RebuildTest (T313 should cover).
- DeviceDiagnostics.refusals keeps rolled-back events; UI must count via refusedCacheCards (T303e).
- Optional contract hardening: pull response echoes `since` (stale page after wipe undetectable; not reachable over HTTP GET).
- (reviewer) DeviceSyncCycle default StartupReplay() builds its own cache/diagnostics → startup refusals miss applier diagnostics; pass StartupReplay(applier.cache) (T303c wiring).
- (reviewer) DeviceCardCache.paramsFor runs outside `guarded`: malformed preset fails the pull page instead of refused cache (schema-invalid input only).
- (reviewer) T303a SyncEngine: reject pull pages not matching requested `since` (stale page after wipe).
- (reviewer) MergeReaders.visibleDeckItems: tombstone-drop vs duplicate-collapse order unspecified in SYNC 8.5 — ask architect, document.

## T303c (architect second pass, 2026-11-13)
- Readers (LocalQueueLoader, DueNotifications, widget) don't await startup replay (§10.5) → stale due dates after upgrade → folded into T304b.
- DurabilityTest WAL+FULL check tests the JDBC driver, not DurableDriver.open; PRAGMA synchronous in onConfigure may hit one pooled connection — verify on device (HUMAN_CHECKS).
- Trigger during a running cycle joins it; op minted after push step waits for next trigger (≤15 min) — add a dirty flag.
- Unreachable mapped to SERVER_ERROR → folded into T303e.
- inState push order by hlc only; add `, op_id` tie-break.
- client.sync_failures sync_state key undocumented in Local.sq/§10.5.
- db/Databases.kt should open the durable driver itself (sync module currently replaces AppDatabase binding); expedited-work notification needs a "Syncing" string (T303e).
- (reviewer) DurableDriver: synchronous=FULL set once in onConfigure; re-assert at start of SqlDelightDeviceStore.run or test after other features touch the DB.
- (reviewer) SqlLearningTables.put keeps absent columns; LearningTables contract says replace whole row (in-memory drops them) — fill NULL/default or require full rows.

## T303d (reviewer, 2026-11-13)
- Remint.blockedBy ignores clock_blocked → preview offers Re-mint that rolls back MINT_REFUSED (no loss) → T303e must hide/disable when clock_blocked; core fix optional.
- remint catches Abort outside its own transaction; called inside an outer tx the in-memory store could commit partial deletes → fail fast in core; T303e must never wrap it.
- RejectedRows OpFacts.decode catches only IllegalStateException; other decode errors crash list()/preview — catch Exception.
- (T303b question resolved) weights_version 0 after card.created post fsrs_params.published conforms (§9.3 lazy marker); readers must replay before reading cache.
- (architect T303d) ClockRepair allows skew == 5 min (<=) but clock_blocked clears only < 5 min → misleading preview (same fix as blockedBy/clock_blocked).
- (architect T303d) OpFacts.isCreation matches any *.created incl. media.created, not in §10.4 list — restrict or confirm.
- (architect T303d) lowerBound falls back to FLOOR when device has no card row — comment or contract clarification.
- (architect T303d) undecodable rows excluded from C → remint_offset deleted even if such a row is clock-rejected (harmless; row stays listed).
- (T310) BlobStore opens a second AndroidSqliteDriver on the app DB (not durable driver); hash mismatch and 404 both → state missing (no failed state in schema).

## T305b (architect, 2026-11-13)
- Retry of a request id whose note is now tombstoned returns Created(retried=true); return tombstone state or treat as new.
- Retry result carries current-call match, not original (cosmetic).
- Unmatched note still mints add_kanji notes for the deck; skip kanji when unmatched.
- DuplicateCheck with no dict match only checks entry-less notes; misses matched note of same expression (§4 MUST NOT knowingly duplicate) — compare expression of matched notes too.
- Open: any server/worker job matching entry-less vocab notes by expression would merge forced duplicates — confirm none does.
- client.note_request.* sync_state keys never pruned.
- (reviewer T305b) non-vocab base noteType skips §4 kanji_literal/grammar_point_id duplicate check; kanjiType deleted_at not checked in add_kanji path; retry returns empty kanjiNoteIds.

## T305c (reviewer, 2026-11-13)
- bulkInsert: ankiapi.db map/counters written before sync-DB outer commit → orphan map rows + inflated counts if a later note throws.
- NoteTypeEnsurer: after fixed-id type deleted, each device mints random UUIDv7 replacement → multiple "Takoboto Vocab" types; derive deterministically (hash(name,kind,"gen2")).
- csum duplicate query does a dictionary lookup per matched note without fields.expression → O(n) per Takoboto call on large collections.
- AnkiIdMap note ids AUTOINCREMENT from 1, not synced → reused after reinstall; Takoboto cached ids may point to other notes; use created_at ms + bump.
- Duplicate add silently drops the new sentence/meaning; log or append via note.updated.
- DuplicateCheck expression fallback misses UI-created matched notes (entry only) when dictionary missing.
- Takoboto debug screen runs store.read in remember{} on main thread.
- ADR-013 (provisional) UUIDv8 note-type id.
- (F-6) T305c's workaround plain query in ankiapi/store/ is now redundant — delete.

## T306+T311 (2026-11-13)
- AnkiConnect allowDuplicate not honoured; storeMediaFile writes blob cache only (no upload/renderer convention); url/path media refused; server not auto-restarted at app start.
## T313
- CLAUDE.md module table: acceptance/ row exists; add `make check MODULE=acceptance` note (human edit).

## T313 (reviewer, minors)
- Sim.kt Ledger.expected(): also require undone (not discarded/replaced) reviews present with undone_at set.
- ScenarioFilesTest: oracle runs with empty ledger (lost-review check vacuous there); record injected review.logged ops in the ledger.
- ScenarioFilesTest: dead `after_seq` skip branch — delete or fail if hit.
- PgSimServer: server-minted op ids random → Pg runs not fully seed-reproducible; seed the op-id source.
- OfflineWeekChaosTest COVERAGE: add minted.review.logged and non-empty expected set.
## T313 (architect, minors)
- ScenarioFilesTest: server verdicts compared only as (op_type, caused); §13 wants equal payloads modulo op_id/hlc/seq/server card id.
- ScenarioFilesTest: `after_seq` rejected entries skipped and examples/remint-scenarios/ not played; judge against server restored to after_seq.
- SimDevice.mint doesn't catch Killed: KILL_AT_COMMIT can fire inside a mint (overlapping action blocks) → spurious failure on other seeds; kill-during-mint/Re-mint untested. Catch, reboot, ledger untouched.
- Reference replica shares DeviceOpApplier with devices; consider tools/validate.py as an independent model over scenario logs.

## T411a (handoff notes)
- Unsatisfiable Range → 416 `range_not_satisfiable`, not in contract error table → add via next C-ticket (C-12 or later).
- S3BlobStore untested against SeaweedFS (tests use in-memory fake); no 302 pre-signed redirects (GET streams).

## T403a / C-11 (handoff notes)
- T403a: fixture suspects (F-7 hand-authored): s131 (富士山 missing from dict fixture), s84/s85/s148/s153/s162/s174/s198 kana lemmas.
- C-11 provisional refinements of ADR-014/015: only server writes media rows; REST jpdb corrections, remap jobs clear dedupe key under advisory lock 7033011; API emits `parsing`, reconciler repairs; deck_item.added_at = deck.created_at.

## T403a (reviewer minors, 2026-11-13)
- parse/deinflect/__init__.py:109 volitional split misses katakana surface (half-width ｲｺｳ → イコウ); test fold_katakana(surface), take tail reading from m.reading.
- parse/normalize.py:405 is_kana(prev) includes ・/゠ and maps ‐/− to ー after them; use hiragana/katakana/ー check.
- parse/segment.py:505 ASCII 名詞 lemma lowercased by Sudachi (A4サイズ → a); keep width-folded case.
- W-5: sudachidict-full is a large runtime dep of the worker image — size the Docker image accordingly.

## C-11 (reviewer/architect minors, 2026-11-13)
- MEDIA_PIPELINE.md §5.4:272 — re-sending the same resolution after a permanently failed remap returns jobs: []; enqueue remap when the media's latest remap job failed.
- MEDIA_PIPELINE.md §5.3 — a `mapped` correction whose entry_seq is later retired by dict-refresh: treat as unresolved at import time or list in `unmapped`.
- (if not taken in C-11 attempt 2) stale-heartbeat requeue fencing (FOR UPDATE + status/locked_by guard); reparseMedia `parsing` version read under 7033001.
- MEDIA_PIPELINE.md: state that deleteMedia's job cancellation takes 7033001 before updating job rows (else waits on runner child-row lock while runner waits on 7033001).

## T404a (2026-11-13)
- publish_replanning allows 4 publishes (3 re-plans); confirm against MEDIA_PIPELINE wording '3 publishes… 4th fails' (possible off-by-one).
- New-media retry re-plans fresh ids → orphan app.blob row per retry (GC later).
- T411c (architect, minor): OpApplier.mediaCreated emits deck.created w/ defaults then runner sends deck.updated — make mediaCreated return none() and have the runner emit deck.created per MEDIA_PIPELINE 3.3 step 6 (also clears T202b silent-skip path).
- T411c (architect, minor): re-claimed child with already-published archives (version + both blob hashes match) succeeds with no ops instead of stale_base — record as ADR / MEDIA_PIPELINE 3.3 step 5 amendment.
- T411c (reviewer, minor): deviation 2 alt fix — return stale_base when payload.parent_job_id differs from the publishing parent, or document in MEDIA_PIPELINE 3.4.
- C-12 (reviewer, minor): PARSER.md 4.4 sync-path `reading` sanitising has no length cap while API OverrideToken.reading maxLength 200 — document or apply the same cap.
- C-12 (architect handoff): C-11 owner adds optional `parser_version` to content archive header; T403b note real Sudachi may tag unknown 𠮷 as punctuation.
- T403b (reviewer, minor): PostgresParseBackend.health learns dict release only at startup/parse → stale `dict_release: null` after refresh until first parse; refresh in background.
- T403b (minor): MatchCache keyed on release version only; freq list reload without new release keeps stale ranks — key on list version or require new release.
- T403b (minor): agreement test — ~100 ambiguous null-seq fixture tokens are wildcards; print wildcard share with score.
- T403b (minor): lone surrogate → 400 invalid_request not in C-12 error table; note in contract follow-up.
- F-17: writer reports worker dict config requires sha256 for the `tatoeba` source; ADR-020 made pins optional — verify consistency.
- T404b: ruby reading hints (ruby: hint) unused — parse_sentences has no hint input; add hint plumbing.
- T406/T408b: SentenceToken lacks word status → web fetches getDictEntry per entry (N requests/page); consider status in sentences/parse response (contract add).
- T406: override UX applies on click without parseText preview/inline editor (deviation for ≤2-click rule); human to judge in HUMAN_CHECKS.

## F-20 (from T505b, run 7) — grammar seed polish (low; fold into T507 or a small server ticket)
- Seeding is only a library call (`GrammarSeeder.seed(opApply)`); nothing invokes it. Add a trigger (first-start opt-in, settings action, or CLI) so the user can actually load the 280 stubs.
- 3 duplicate (jlpt,title) pairs in fixtures/grammar-seed (N2 "cannot help but do"; N1 "not to mention"; N1 "without, formal"): retitle, re-copy to server/src/main/resources/grammar-seed/, make validate.py + GrammarApiTest check unique titles per level.

## F-21 (from T503, run 7) — dedupe hot-word loading (low)
- server drill/DrillSources.kt duplicates StruggleModule's private HotWords loading/scoring; extract one shared server helper. Also `DrillSelector.word` is unused (request schema has no single-word source; hot word page "extra drill" may need it → C-ticket if T504 needs it).

## F-22 (from T506a review, run 7) — grammar detection gaps (med-ish; do before GATE-P5) — DONE run 9
- media-parse/media-import publish no `grammar_spans` → a re-parse wipes detected spans and new media get none until a rule edit fan-out (V1 comment: replaced "on every (re-)parse"). Fix: call `detect_spans` in media-parse `plan_request`.
- matcher.py `to_mtoken` drops every `*` in pos (also middle levels) → deep `[pos:A-B-C]` rules mis-match. Drop trailing `*` only / treat as wildcard.
- matcher backtracking budget exhaustion silently = no match; count and report in job result.
- F-22 status (run 7): item 1 FIXED in T506a#2 (detection in parse/import plan_request). Items 2–3 (pos `*` levels, budget give-ups) may be partly done — check T506a handoff; give-ups are logged only (result schema forbids extra fields).

## F-23 (from T506a re-review, run 7) — grammar-detect dedupe A→B→A (low, contract)
- Rules edited A→B→A while the per-media job for B runs: the revert's enqueue hits the running job's key and is dropped → media keeps B spans. Fix via C-ticket: key includes the rules' hlc, or the fan-out clears running per-media keys.

## F-24 (from C-14 review, run 8) — rule-test contract polish (low)
- worker-parse.yaml §5 mapping: server must check existing `id` (idempotent 200) on createGrammarPoint BEFORE calling the worker, else retries get 503/422. (Passed to T506c.)
- PARSER.md §5.1 still says "any other path is 404"; align with worker-parse 1.1.0.
- openapi DetectionRule.rule: 1000 code points + "grammar owned by the core" vs worker cap 500 / worker-owned DSL; align.
- WorkerRuleTestSentence.tokens maxItems 20000 < possible 40000 UTF-16 units; raise or have server skip such sentences.

## F-25 (from T507, run 8) — wire grammar card sentence rotation into review (med; before GATE-P5)
- server grammar/cards/GrammarCardSentenceSource (rotation, i+0) has no caller: no API op returns the per-review sentence for a grammar card, so clients still show a fixed sentence. Add the op (C-ticket if openapi lacks it) and use it in web/Android review. Tatoeba sentences have no spans → excluded for now.
- F-25 additions (C-15 review, run 9): (a) core GrammarCardSentences.pick with rotation=reps + exclude lastShown skips sentences (A,B,C → A,C,A,C; drills alternate 2) — rotate over full tier stepping past lastShown; (b) order candidates by (sentence_id, start_offset, end_offset) for server/Android agreement; (c) C-15 notes live in contracts/api/SUMMARY.md (no contracts/SUMMARY.md).
## F-27 (from T504b, run 9) — Android struggle writes online-only (low)
- Struggle screen writes (PATCH note, PUT confusable, POST note, drill POST review/answer) go via server REST, not device ops/outbox → fail offline. Move to OpMinter like review. Widget hot word uses local lapse stand-in, not server hot score. Uses hand JSON ApiClient, not generated client.

## T603 review minors (run 10)
- ankiconnect: 5xx ApiException rethrown -> HTTP 500; AnkiConnect contract says always HTTP 200 (AnkiConnectModule.kt:86)
- ankiconnect: addNote audio/picture attachments skip the 100 MB cap (only storeMediaFile checks it)
- ankiconnect: allowDuplicate re-emits deck.item_put, moving item to deck end (same in POST /notes)
- ankiconnect: duplicate check trims new first field but not stored values
- ankiconnect: no 100-tag cap (NoteApi has one)
- ankiconnect: any 64-hex filename rewritten to blob ref even if blob missing (AnkiMedia.kt)
- ankiconnect: original-filename map in memory, lost on restart/multi-replica
- ankiconnect: SQL dict-entry matcher stands in for a core matcher; replace when core has one

## T601 notes (run 10)
- voicevox image tag cpu-0.21.1 unverified; pod root fs writable; only numeric speaker ids; PG cache/sink SQL untested on real DB; deploy/helm/check.sh not extended for voicevox

## T608 review minors (run 10)
- export/apkg: collectBlobs scans note fields only; blob: images in mnemonics rendered into kb_back_* are silently omitted (PRIORITY: silent omission)
- export/apkg: store.get null after size check writes empty media file silently
- export/apkg: due days from UTC midnight, not user tz/rollover (off-by-one possible)
- export/apkg: cards/notes/revlog held in memory, not streamed
- export: JsonDump.open blocking JDBC on request coroutine (no Dispatchers.IO); pooled conn held for whole download
- export: mid-stream DB failure -> truncated JSON with 200
- export/apkg: relearning reviews written as revlog type 0 not 2; ivl/lastIvl/factor = 0
- export: no test that a token without read scope gets 403

## T606 review minors + notes (run 10)
- optimizer/store.py: Default preset run misses cards whose preset is tombstoned (effective preset = Default) -> lost training data only
- optimizer/store.py: reviews of tombstoned/merged-away cards not filtered (possible double-counted history)
- optimizer/model.py: eval uses 24h elapsed days vs core's user day boundaries (gate still fair); document
- optimizer: PgStore, enqueue_all, helm cron untested; deploy/helm/check.sh has no optimizer CronJob assertion
- worker image: fsrs[optimizer] pulls torch; uv.lock resolves CUDA wheels, Dockerfile plain pip -> multi-GB image. Use CPU-only torch index before deploying (HUMAN_CHECK)

## F-28 notes (run 11)
- worker/Dockerfile hardcodes torch==2.15.0 (CPU index); bump together with uv.lock, or switch Dockerfile to `uv sync --frozen`. (low)

## T609 review minors + deferred SEC items (run 11)
- egress.internet IPv4-only; add commented ::/0 (private excluded) for dual-stack. (low)
- ntfy.baseUrl empty -> no base-url; attachments/web links break (SEC-9). (low)
- backupTo egress defaults to anywhere; narrow to restic target/ntfy/apiserver when backups go live. (low)
- SEC-6: narrow ingressFrom + api.trustedProxies to gateway pods; persist login limiter/TOTP step. (low)
- SEC-4 (C-ticket): dedicated `export` scope or require admin for exportAll/exportAnki. (low)
- SEC-5 (C-ticket): AnkiConnect contract drift (`url` media unsupported; OPTIONS preflight). (low)
- SEC-7 web: nginx.conf with CSP/nosniff/Referrer-Policy (ingress deployments). (low)
- SEC-10: try voicevox readOnlyRootFilesystem with emptyDirs. (info)  SEC-13: emptyDir sizeLimits. (info)

## T610 runbook gaps (run 11)
- No FSRS weight reset/rollback path in the contract (bad optimizer publish). (med)
- Chart renders no job-stuck or disk alert; no dictionary-refresh CronJob. (low)

## SEC-1 review minors (run 11)
- Non-inline blobs still send stored active Content-Type (text/html, svg); also send application/octet-stream (defence in depth). (low)
- Tests: assert hardening headers on 404/416 (StatusPages path) and Content-Disposition on 304. (low)
- getBlob contract could document the security headers (C-ticket, doc only). (low)
- docs/SECURITY.md: mark S-01 (SEC-1) and S-02 (SEC-2) fixed. (low)

## BUG-1 review minors (run 13)
- StatsPerfTest asserts < 1000 ms hard (known-day ~590 ms); consider < 2000 ms assert + log vs 1 s target to avoid flaky fails on slow hosts.
- StatsModule streak `fetchSize(500)` only pages with autocommit off; pin the transaction (explicit tx or test).
- StatsQueries.loadHistory passes all roots in one uuid[] (~10k); unbounded — chunk if it grows.

## BUG-6 architect note (run 13)
- Rolling deploy with 2 server pods: old pod could run a jpdb import after new pod stored fsrs6-2, writing old-rule caches under the new version. Single replica today; use Recreate strategy (or re-check) to rule it out.
- AND-DECKS (run 15): deck create/rename/delete not on Android (read-only tab); smart-deck counts empty on device (no on-device resolver); deck words from replica notes only (no API fallback).
- FEAT-1 (run 15): 'other users' decks' case untestable (single-user schema); deck dropdown lists smart decks (empty page per contract); clickable kanji in review cards/Android still out of scope.
- AND-SEARCH (run 15): word/{seq} not a nav route (NavRoute.content has no args; detail is in-screen state); is:hot + live smart-deck terms not compiled on device; no 'Add to my words'; API card fallback first 100 only.
- AND-SETTINGS (run 15): QR scanner launch not exercised in tests (Robolectric renders Devices/Scan QR only) -> HUMAN_CHECKS device check; no licences screen for About link.
- HOTFIX-0.1.7 (run 17): preview effect-loop regression vitest (mount CardPreview + NoteEditor with dict-backed note, bounded effect runs) NOT added: adding devDependency jsdom was denied by the permission classifier. Human approved jsdom 2026-11-13 -> TEST-1 MERGED b43dbca (done).
- BUG-9 (run 17): no constrained -Xmx256m test run (server/build.gradle.kts maxHeapSize 2g outside paths); bound shown by chunked design + 335 MB peak in 2 GB test JVM. known-words?interval=day slowest (607 ms).
- BUG-10 (run 17): /kanji/{lit}/words 1.4 s not reproduced on seed (18-37 ms) -> verify live after 0.1.8; /words/hot?limit=200 209 ms warm (target 200); perf test bound 1 s (cold JIT).
- BUG-8 review (run 17, minor): no core test splitting a pull page into chunks (pullChunkOps=2) with a crash in chunk 2 + resume, checking rows and pull_seq equal a one-tx pull (logic traced OK by reviewer). Worth a small core test ticket (sync data-loss guard).
- BUG-8 review (run 17, minor): DeviceOpApplier.open is a plain shared field, safe only because batches run inside the single SQLite writer tx; add comment or @Volatile + assertion.
- BUG-8 review (run 17, minors 2-5): DbThreadGuard wraps only SYNC_DRIVER (dict DB / SharedPreferences main-thread access undetected); SyncStatusRepository read{} not one snapshot (display only); onProgress vs refresh race on state.value (chip may step back; use state.update); PairingFeature unpair() launched on IO not awaited before paired=false. -> folded into UI-FOUND-APP ticket.
- UI-FOUND-WEB (run 17): view rewrite (~45 files) has no e2e coverage; eslint now parses .svelte.ts and surfaces existing prefer-svelte-reactivity errors (lint not a gate); screenshot harness needs CHROMIUM_PATH (system Chromium).

## UI-WEB (run 19, writer notes)
- Confusable headwords in API (UI-WEB state gap; needs server/API change)
- Deck colour + due count in Deck API/state (UI-WEB state gap)

## UI-APP (run 19, state-layer requests -> candidate ticket APP-STATE-1, android ViewModels)
- ReviewMeta: remaining count + deck name ("12 of 85") and next-due time
- HomeUiState: overall due/new counts, 14-day activity
- Stats ViewModel (Android has no stats screen yet)
- WordDetail: pitch + kanji readings
- Deck colour (shared with the web gap)
- UI-APP review minors (run 19): settings-notify icon tile squeezes text at 200%; Settings rows reuse one sync icon / single-row groups; deck-detail word rows lack reading/gloss and show sentences as words; "All done" review state needs Study ahead/Browse + next-due (if not done in attempt 2)
- UI-WEB (run 19): DeckItems + UnknownWords state have no load-error flag; client-side nav /cards -> /notes shows id-prefix labels in the notes list (full load fine)
- UI-WEB review minor (run 19): nav rail icons fixed px while labels scale at 200% (size in rem)
- UI-APP (run 19): grammar capture entry from Search/intake needs sentence hits with id/mediaId (or a create-sentence call); Today overline study date computed once per composition (stale past rollover if app stays open)
- UI-WEB (run 19): commit the override-redundancy vitest the round-2 writer ran one-off (NoteEditor dropRedundantOverrides; web/tests)

## Run 20 live sweep (0.1.9, 2026-11-14)
- dict-refresh failed once 11-13 "ChecksumMismatch: kanjidic2.xml.gz sha256" (later run succeeded) — check whether a pinned checksum can break the next refresh when upstream republishes. low/med.
- fsrs-optimize failed once 11-13 "datetime must be timezone-aware and set to UTC" (later run succeeded) — find the naive-datetime path (likely a job param/ default). low.
- media-publish 23:31 succeeded with tokens 0 / sentences 0 — child of the broken jpdb import (jpdb.vocab []); BUG-11 covers. Recheck after 0.1.10.
- API 404s on /api/health, /api/version, /api/healthz (external probe, wrong paths); /readyz 200. low.
- Frequency list decks: live values.yaml dictSourcesJson has no `freq_lists` → freq_list/entry_freq empty. Config; handled with JLPT-1 deploy.
- BUG-11 review minors: (1) pages.py:142 overview page cached without `accept` (link-regex) check → challenge page poisons cache (remap_only forever); (2) pages.py:55-61 deck_slug treats any 3rd segment (e.g. /anime/5370/stats) as slug — loud failure; (3) pages.py:164 title fallback is raw URL, use deck_path(jpdb_id); (4) no test that re-import refills an existing empty jpdb deck; (5) first page with rows all skipped still publishes 0 words (reported) — consider failing.
- C-21 review minors: (1) VOCAB_LISTS 3.4 deck-build planned on old release can publish stale membership after a concurrent refresh (self-heals next refresh) — add release_id to deck-publish payload, runner fails stale_release; (2) 3.3 configured jlpt source with header only → source_rows 0 succeeds and empties lists — fail invalid_source; (3) /deck-lists hides item_count 0 keys and createDeck 422s for them — release note; (4) 4.3 JLPT order falls back to kana order when no freq list loaded — configure freq_lists.
- BUG-14a review minor: retrying with a different rating after an ambiguous failed POST reuses the answerId; if the first POST reached the server the second rating is silently dropped (server returns the first). low.
- BUG-14a review r2 minor: refetch fails after grading the last item → empty list may show 'done' while cards remain due; show reload prompt instead. low.
