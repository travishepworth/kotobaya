# INBOX — live triage of 0.1.6 (started 2026-11-13)

> **Orchestrator:** read this whole file. The **Run 17 brief** at the bottom is the human's instruction
> for this run, and it overrides earlier plan state (UI-1 and FEAT-x are no longer HUMAN-GATED). The
> issue list (I-1 … I-7) is the evidence the tickets cite. Turn the items into tickets, then archive
> this file to plan/handoffs/INBOX-0.1.6.md so nothing is tracked in two places.

A running list of problems the user found while using the live deploy, plus findings from the logs.
Not yet ticketed. The orchestrator turns these into BUG-/FEAT- tickets in BACKLOG.md.
Each item is tagged with the track it belongs to:
- **[fix-now]**: functional bug in server, core or worker logic. It survives any UI rewrite, so fix it now.
- **[ui-rework]**: belongs to the planned full redesign of the web and Android UI (UI-1). The current
  screens are proof-of-concept only, so don't polish them. Record the requirement instead.
- **[both]**: needs a server/core fix now, and the redesign has to account for it.

## Open

### I-1 [fix-now] Stats: /stats/retention returns 500 (OutOfMemoryError); stats panels take 7–12 s
- Evidence (api log, 11:48 and 11:49 UTC): `java.lang.OutOfMemoryError: Java heap space` in a jOOQ
  record alloc. `GET /stats/retention` returns 500 after 6.9–11.9 s. heatmap, calibration and known-words
  return 200 but take 7–12 s. The OOM also killed a JobsModule coroutine thread
  (`DefaultDispatcher-worker-3`, JobsModule.kt:70), so the jobs poller may be dead until the api restarts.
  Check this.
- Cause: the JVM runs with default ergonomics, so MaxHeapSize = 256 MB (25% of the 1 Gi limit).
  `scheduledReviews` (server/.../stats/StatsQueries.kt:135) loads the **full review history** of every
  card reviewed in the window and replays it in memory. The stats page runs retention, calibration and
  heatmap in parallel, so there are about 3 concurrent full replays of 78,597 reviews / 9,531 cards.
- Fix (a), deploy-only stopgap: add `api.env.JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=70"` to
  ~/repos/kubernetes/kotobaya/values.yaml, then helm upgrade. Not applied yet; waiting on the user.
- Fix (b), server: compute stats from cached card state / a precomputed per-review "interval before",
  or stream the replay card by card instead of materialising all history. Share one pass between
  retention and calibration. Set a sane default heap in the image (`JAVA_TOOL_OPTIONS` in
  server/Dockerfile or the chart). Add a soft memory guard.

### I-2 [fix-now] /words/hot and /kanji/hot take 1.4–3.9 s (10 s under load)
- Evidence: hot words and hot kanji take about 1.5 s on every dashboard load. Hot kanji took 8.9 s and
  hot words 10.2 s while stats was running. The user reports the app feels "slower than before"; this is
  probably most of it (with the GC thrash from I-1).
- `GET /kanji/{literal}/words` took 1.4 s (FEAT-1). Check its query plan.

### I-3 [fix-now] Card/note previews crash the whole page
- User report: on /cards and /notes, opening a preview kills the page.
- Server side is clean: `/dict/entries/{seq}` and `/dict/headwords` return 200 in under 15 ms.
- **Root cause (reproduced in node with the repo's Svelte runtime):** a Svelte 5 `$effect` loops
  forever. The effect writes `loading` and then reads it back (`loading = seq !== null || lit !== null;
  if (!loading) return;`), so `loading` becomes a dependency of the effect. When the fetch's `finally`
  sets `loading = false`, the effect re-runs, resets its state and refetches. `fetchEntry` is cached, so
  the refetch resolves in a microtask, the loop never yields, and the tab hangs.
  - web/src/lib/cards/CardPreview.svelte:28-29
  - web/src/lib/notes/NoteEditor.svelte:63-64 (`dictLoading`). This one is worse: every pass calls
    `onentry` → `headwords.put`, which re-renders the NoteTable on each loop.
  - Hits every note with an entrySeq or kanjiLiteral, which is every jpdb vocab and kanji note.
- User confirms: nothing appears in DevTools; the page stops and the browser slows down. That matches
  a microtask loop (no exception is thrown, and Svelte's update-depth guard never fires).
- Fix: use a local `const busy = seq !== null || lit !== null; loading = busy; if (!busy) return;`
  (or `untrack`). Never read state inside the effect that writes it.
- Lesson for UI-1: no test caught this, because UI code ships untested. The redesign wants a smoke test
  that mounts each preview with a dictionary-backed note.

### I-4 [fix-now] Android first sync takes many minutes; app stops responding (ANR) during it
- User report: the app spent about 5 minutes syncing and then stopped responding before the sync
  finished, so review couldn't be tested. One push notification came through.
- Server is fine: about 50 `GET /sync/pull` requests between 11:53 and 12:00 UTC, each served in
  12–90 ms. But they arrive about 10 s apart, so **the time is spent on the device applying each page**.
  The server has app.change = 111,883 ops (78,597 reviews). At this rate a fresh device needs tens of
  minutes to catch up.
- Suspects: one SQLite transaction per op, IncrementalReplay re-folding card history on every review op,
  or the startup "recompute card states" running inside the pull loop. The ANR suggests the main thread
  is blocked on the DB (a sync transaction holds the connection while the UI queries).
- Fix direction: apply a whole pull page in one transaction; defer card-cache replay to the end of the
  cycle (once per touched card); keep all DB work off the main thread; show "Syncing N/M" progress.
  Consider a snapshot bootstrap for first sync, though that changes the contract.
- **Confirmed from the ANR traces** (bugreport, /data/anr/anr_2026-11-13-20-58-52 and -21-02-01;
  copy in the session scratchpad br/):
  - Sync holds the **only SQLite connection** for a whole pull page: `SyncEngine.pullAll` →
    `SqlDelightDeviceStore.transaction` (SyncEngine.kt:163). Each page takes about 10 s.
  - The main thread then blocks in `SQLiteConnectionPool.waitForConnection`, called from
    `TakobotoDebug` (android/.../ankiapi/takoboto/TakobotoFeature.kt:105). That composable runs
    `store.read{ learning.all(NOTE_TYPE) }` synchronously **inside composition** on the Settings screen.
    Any screen that reads the DB on the main thread freezes the same way: this is the "pages lag and
    don't switch" report.
  - Per-page hot spots in the sync thread:
    - `AutoStatus.noteIdsOf` → `SqlLearningTables.select` (SqlLearningTables.kt:173), called from
      `DeviceOpApplier.applyTo:68` for every op. Probably an unindexed scan per review.
    - `SyncSchemas.validate` → recursive `check` in `DefaultOpCodec.decodeTree` (DefaultOpCodec.kt:120)
      for every pulled op.
- Fixes:
  1. No DB access on the main thread or inside composition: move it to `Dispatchers.IO` and expose
     it as state/Flow.
  2. Enable WAL with separate reader connections, so reads don't wait for the sync writer.
  3. Use smaller transactions, or yield between chunks of a page.
  4. Index or cache AutoStatus lookups.
  5. Make schema validation cheap for trusted server-pulled ops, or at least not quadratic.
     **This touches the sync client, so it's high risk and needs an architect review.**

### I-7 [fix-now] Android pages lag and don't switch while sync runs
- Same root cause as I-4: main-thread DB reads wait behind the sync transaction.

### I-5 [ui-rework] Android Media tab shows nothing; probably remove it
- The user suggests removing the tab. Decide in the UI-1 information architecture (media import is a
  web/desktop task).

### I-6 [ui-rework] Dark mode on Android and web
- Both apps need light and dark themes that follow the system setting. The card CSS in assets/card/ needs dark tokens too.

### Verified OK (0.1.6, phone)
- Android Search and Settings work.

## UI rework notes (input for UI-1; record only, don't build on the POC screens)
- The user plans a complete re-creation of the web UI and the Android app UI: the current ones are
  POC, unpleasant to use, and not pretty. Organise the code with that in mind: keep logic out of the
  view components (core / API layer / view-model stores) so the views can be replaced wholesale.

---

# Run 17 brief from the human: live triage of 0.1.6, plus the UI rewrite (UI-1 is now UNGATED)

## Track A: fix-now bugs (dispatch first, in this order)

A0. HOTFIX-0.1.7 (writer-light, low risk, web + deploy only). Ship as soon as it merges.
  - Previews hang the browser (I-3): Svelte 5 $effect reads state it writes, so it loops forever.
    Files: web/src/lib/cards/CardPreview.svelte:28-29 and web/src/lib/notes/NoteEditor.svelte:63-64.
    Fix: test a local `busy`, never the state you just wrote (or use untrack).
    Add one vitest that mounts each preview with a dictionary-backed note and asserts the effect runs a
    bounded number of times. This is a regression the human hit live.
  - API heap (I-1 stopgap): the chart defaults to JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 for the api
    (the JVM currently gets 256MB of a 1Gi limit). Put it in the chart default, overridable through api.env.
  - Add a HUMAN_CHECKS entry for the deploy.

A1. BUG-8 (writer-senior, HIGH risk, architect second review): Android sync and main-thread DB access
  (I-4, I-7).
  - First sync of 112k ops takes tens of minutes, about 10 s per pull page on the device. The app ANRs
    because sync holds the only SQLite connection for a whole page while composables read the DB on the
    main thread (TakobotoFeature.kt:105 is one).
  - Required:
    - no DB access on the main thread or inside composition anywhere in android/ (audit every screen)
    - WAL with reader connections, so reads never wait for the sync writer
    - pull pages applied in bounded transactions that yield
    - AutoStatus lookup (SqlLearningTables.kt:173 via DeviceOpApplier.applyTo:68) indexed or batched
    - SyncSchemas.validate cost per pulled op cut (must stay correct; architect signs off)
    - visible "Syncing N of M" progress
  - Acceptance: the SYNC.md worked examples and the convergence fuzz stay green. A synthetic 100k-op pull
    applies in under 2 min on a JVM/Robolectric bench (report the real number). No StrictMode disk-read
    violations on the main thread in debug.
  - If a snapshot bootstrap for first sync is needed, that's a CONTRACT_CHANGE_REQUEST: architect ticket
    first.

A2. BUG-9 (writer, medium): stats memory and speed (I-1 real fix).
  - Today `scheduledReviews` (server/.../stats/StatsQueries.kt:135) loads and replays the full history of
    every card, three times in parallel. Compute retention and calibration in one bounded pass instead:
    stream card by card, or persist interval_before/retrievability at apply time.
  - Acceptance: /stats/* each under 1 s on 80k reviews, with heap use bounded (Testcontainers test with
    seeded volume). Golden equality with the current outputs on the fixture set.

A3. BUG-10 (writer, medium): /words/hot and /kanji/hot take about 1.5 s on every dashboard load (10 s
  under load), and GET /kanji/{literal}/words takes 1.4 s (I-2). EXPLAIN them, add indexes or a cached
  rollup. Target: under 200 ms.

Push main and deploy A0 as 0.1.7 right away. A1–A3 can go out together as 0.1.8.

## Track B: UI rewrite (UI-1, ungated by the human today)

Context, said plainly: the current web and Android UIs are throwaway proof-of-concept screens. They are
ugly, hard to read, don't scale, and are unpleasant to use. Nothing about their look, layout or component
structure is worth keeping. The goal is a genuinely beautiful, calm, modern study app that someone would
choose over Anki and jpdb on looks and feel alone. Polish is the deliverable, not a nice-to-have.

Order: B1 → B2 → STOP for human approval → (B3 ∥ B4). B1 can run alongside Track A once A0 merges.
B4 also needs A1 merged.

B1. UI-FOUND (writer-senior): foundation, so the design agents only design.
  - Web: one typed state module per screen under web/src/lib/state/<screen>.ts. It owns all fetching,
    caching, core calls (renderCard/parseQuery/previewIntervals) and mutations, and exposes plain reactive
    state and actions. Views import only from state/ and ui/. No fetch, client or core imports in .svelte
    files.
  - Android: one ViewModel per screen exposing an immutable UiState (StateFlow) and intents. Composables
    take state plus callbacks only: no Koin, no DB, no repositories. All IO goes through Dispatchers.IO.
  - A demo/fixture dataset (no private data) with deliberately awkward content:
    - long multi-sense glosses, rare kanji, very long readings
    - 30+ decks, an empty account, a 900-due day
    - kanji notes, dictionary-backed vocab with empty fields, audio/no audio
  - Web runs against it with a mock-server mode (`make dev-ui` or similar). Android exposes it through
    Compose previews and a debug "demo data" switch.
  - Screenshot harnesses:
    - web: Playwright screenshotting every route
    - Android: Roborazzi or Paparazzi screenshotting every screen
    - matrix: widths 360 / 768 / 1280 / 1920 (Android: phone, foldable, tablet) × light/dark × font scale
      1.0/2.0
    - note the new dev dependencies in HANDOFF
    - this is the design feedback loop, not a CI gate
  - No visual changes in this ticket. Behaviour stays identical.

B2. UI-DS (writer-senior, Opus): design system, information architecture, and the visual plan. Produce
  docs/DESIGN.md and tokens.
  - One token source (JSON) generating both CSS custom properties (web + assets/card/card.css) and the
    Compose theme (colours, type, shapes, spacing, motion). Light and dark from day one, following the
    system theme with a manual override.
  - Typography designed for Japanese first:
    - JP/Latin font pairing (respect assets/card/fonts.txt licences)
    - furigana/ruby sizing
    - comfortable line-height for kana
    - a fluid type scale (clamp) on web and sp-based sizes on Android that survive 200% font scale
  - Colour: restrained, high-contrast, WCAG AA in both themes. Semantic tokens for grading (again/hard/
    good/easy), card states, pitch accent and deck colours. Colour is never the only signal.
  - Components spec: button, card surface, list row, tabs/segmented control, chip, input, sheet/dialog,
    toast, empty and loading states, data table (web).
  - Information architecture: the top-level destinations per platform. Default proposal: drop the Android
    Media tab (I-5), since media import is web-only. Web gets a real app shell (sidebar on desktop,
    bottom/compact nav on mobile widths).
  - The visual plan: in DESIGN.md, a section per screen (Review first) describing how it will look and
    behave, with the B1 screenshot matrix as the acceptance check. That covers layout at each width,
    hierarchy, motion, and the empty/loading/error states.
  - Deliverable includes a /styleguide web route and an Android debug styleguide screen rendering every
    token and component in both themes, plus screenshots of both.

  ** HUMAN GATE (hard): after B2 merges, do NOT dispatch B3 or B4. **
  - Tell the human it is ready to read: in your run summary AND at the top of plan/HUMAN_CHECKS.md.
    Give links/paths to docs/DESIGN.md, the /styleguide route (and how to open it locally or on the
    deploy), and the screenshot folder.
  - Mark B3/B4 HUMAN-GATED in BACKLOG ("waiting for human approval of DESIGN.md + styleguide").
  - The human will message you once they have read and approved the style guide and the plan. Only then
    dispatch B3 and B4, folding in any changes they ask for. Until then, keep working Track A and other
    runnable tickets; don't idle on the gate.

B3. UI-WEB (writer-senior, Opus) and B4. UI-APP (writer-senior, Opus): rebuild every screen from scratch on
  B2 tokens and B1 state. One agent each, running in parallel, after the human approves B2.
  - Owned paths: view files only (web/src/routes/**, web/src/lib/ui/**; android/**/ui/**, nav/, theme).
    They may NOT change state modules, ViewModels, core, server or contracts. If something is missing,
    return CONTRACT_CHANGE_REQUEST / a state-layer request instead of hacking around it.
  - Tell them explicitly: the existing screens are bad; do not preserve their layout. Design each screen
    as if new, following the approved DESIGN.md, with these priorities:
    1. Review is the product. Full-bleed card, generous type, the answer reveal is a satisfying moment.
       Grade buttons sit in the thumb zone on phones with interval previews. Keyboard-first on desktop
       (space/1-4 with visible hints). Smooth transitions, no layout jump between front and back, progress
       that motivates without nagging. Works one-handed.
    2. Dictionary and kanji pages are beautiful to read: clear headword/reading/pitch hierarchy, senses
       scannable, clickable kanji panel, in-deck markers.
    3. Cards/Notes browser: a real master–detail, with the preview pane rendering the actual card (fold in
       FEAT-x: WYSIWYG per-field override; needs the core render-contract C-xx from the architect first).
    4. Home/dashboard and stats: today-at-a-glance, readable charts in both themes.
    5. Decks, settings, import, devices: tidy, consistent, boring in the good way.
  - Non-negotiable quality bar for every screen, checked with the B1 screenshot matrix and attached to the
    handoff:
    - looks deliberate and polished at every width, from 360 px phones to 1920 px desktop, and on Android
      phone, foldable and tablet
    - no horizontal scroll, no clipped or overlapping text, no tiny tap targets (48dp/44px minimum)
    - readable at 200% font scale; WCAG AA contrast in light and dark; visible focus states
    - every list has designed empty, loading and error states
    - no layout shift on load
    - Japanese text never breaks mid-word or renders in a fallback CJK font
    - motion is subtle, under 250 ms, and respects reduced-motion
    - all strings through i18n
  - Process: build Review first, attach screenshots, then the rest. Before handing off, review your own
    screenshots critically against the bar above and fix what's ugly. "Functional" is not done. The
    reviewer agent gets the screenshot set and the bar, and rejects anything that looks amateur or
    scales badly (that counts as a major for these tickets).

## Notes
- UI-1 and FEAT-x are no longer HUMAN-GATED. FEAT-x merges into B3, with a core render-contract ticket
  (architect) before it.
- The old POC UI code can be deleted wholesale in B3/B4. Don't carry dead screens.
- Deploys: build and helm from one pinned SHA, never reuse a tag.
