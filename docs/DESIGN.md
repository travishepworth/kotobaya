# Kotobaya design system and visual plan

Status: **draft for human review** (UI-DS, run 17). Nothing here is built into the screens yet. UI-WEB (web)
and UI-APP (Android) rebuild every screen on this document after it is approved.

- Token source: `design/tokens/tokens.json` → `make tokens` generates `web/src/lib/tokens.css`, the token block of
  `assets/card/card.css`, `web/src/lib/ui/tokens.generated.ts` and
  `android/src/main/kotlin/kotobaya/android/ui/theme/Tokens.kt`. `make check MODULE=web` fails on drift and on
  any colour pair below WCAG AA (`design/tokens/tokens.test.mjs`).
- Living reference: the `/styleguide` route (`make dev-ui`, then http://localhost:3000/styleguide).
- Screenshots: `docs/design-screenshots/web/` (curated); `make screenshots-web` writes the full matrix to
  `web/screenshots/` (gitignored).
- Web primitives: `web/src/lib/ui/` (Svelte 5). The Android styleguide screen is UI-DS-APP.

---

## 1. Principles

1. **Review is the product.** Every other screen exists to feed or explain it. The review card gets the most
   space, the biggest type and the only "moment" in the app (the reveal).
2. **Paper and ink.** Warm off-white paper and sumi ink in light; a warm near-black (never pure #000) in dark.
   Surfaces are separated by hairlines and tone, not by heavy shadows.
3. **One action colour.** Indigo (藍 *ai*) is the only colour for things you press. Vermilion (朱 *shu*) is the
   seal: wordmark, the cloze, the "all done" ensō. It is never a button colour (the delete button uses
   `danger`, which shares the hue but reads as a warning, and always says what it deletes).
4. **Colour is never the only signal.** Grades carry a label, an interval and a key; card states carry a glyph
   and a word; pitch patterns carry a number and a name; deck colours sit next to the deck name.
5. **Japanese first.** Japanese text is set in a Japanese face with `lang="ja"`, never a fallback CJK font
   (no Chinese glyph variants), never broken mid-word, with room for furigana.
6. **Quiet motion.** Nothing over 250 ms. Movement only where it explains (the reveal, a sheet rising);
   reduced motion turns movement into fades.
7. **Boring in the good way.** Settings, import and devices use the same list rows and forms everywhere.

## 2. Tokens

All names below are CSS custom properties on the web (`--kb-…`) and members of `KbColors`, `KbType`, `KbSpace`,
`KbRadius`, `KbMotion`, `KbSize`, `KbBreakpoint` in Compose.

### 2.1 Colour

Palette primitives (paper, stone, sumi, ai, shu, kohaku, matcha, sora, fuji, sakura, aoi) are never used
directly by components; only the semantic colours are. Every semantic colour has a light and a dark value.

| Group | Tokens | Notes |
|---|---|---|
| Surfaces | `bg`, `surface`, `surface-sunken`, `surface-raised`, `surface-hover`, `surface-selected`, `border`, `border-strong`, `scrim` | `bg` is the canvas; panels and the review card are `surface`. `border` is decorative; control edges use `border-strong` (≥ 3:1). |
| Text | `text`, `text-muted`, `text-disabled` | `text-muted` is ≥ 4.5:1 on every surface, so it can carry readings and metadata. |
| Action | `primary`, `on-primary`, `primary-hover`, `primary-container`, `on-primary-container`, `link`, `focus`, `brand` | Dark theme flips to pale indigo buttons with ink labels. |
| Feedback | `danger(-container)`, `warning-container`, `success-container` (+ `on-…`) | Banners and toasts. |
| Grades | `grade-{again,hard,good,easy}` + `-container` | again = shu, hard = kohaku (amber), good = matcha, easy = sora. |
| Card states | `state-{new,learning,review,relearning,suspended,leech}` + `-container` | Chips with glyphs: ✦ new, clock learning, ✓ review, ↺ relearning, ‖ suspended, ⚑ leech. |
| Pitch | `pitch-line`, `pitch-{heiban,atamadaka,nakadaka,odaka}` | Conventional colours (blue, red, amber, green) plus the number and name. |
| Highlight | `highlight`, `on-highlight` | Card spot highlight, search hits. |
| Decks | `deck-{ai,shu,matcha,kohaku,fuji,sora,sakura,aoi,hai}` + `-container` | Nine user-pickable deck colours; `hai` (ash) is the default. |

Contrast: `tokens.json › contrast` declares every pair the design uses (text 4.5:1, non-text 3:1); the test
also fails when a new `on-X` or `X-container` colour is added without declaring its pair. Current tightest
pair: deck-shu on its container, 5.08:1 (light). The `/styleguide` Contrast table lists them all.

### 2.2 Typography

- **Faces:** Noto Sans JP (UI, Latin and Japanese; its Latin is clean and weight-matched) and Noto Serif JP
  (only the kanji literal and the 言 seal). Both are OFL, listed in `assets/card/fonts.txt`. Web self-hosts them
  as variable fonts split by `unicode-range` (`@fontsource-variable/noto-{sans,serif}-jp`, imported by
  `web/src/lib/ui/fonts.ts`; UI-WEB imports it once in the root layout). The stack falls back to Noto Sans CJK
  JP / Hiragino / Yu Gothic, never to a generic CJK font first. Android bundles subset Noto Sans JP / Serif JP
  in `res/font` (UI-APP) and passes them to `kbTypography(sans, serif)`; until then the system Noto CJK is used,
  with `LocaleList("ja")` on Japanese `Text` so Han glyphs are Japanese.
- **Scale** (`--kb-type-<role>` is a `font` shorthand; `-size`, `-lh`, `-weight`, `-tracking` are also exposed).
  Web sizes are rem and fluid between 360 and 1280 px viewport (`clamp()`), so they follow the browser text size;
  Android uses the same roles in sp.

| Role | Web (rem) | Android | Use |
|---|---|---|---|
| jp-hero | 4.5 → 7 | 80 sp | Kanji literal (serif) |
| jp-display | 2.25 → 3.5 | 40 sp | Review headword, dictionary headword |
| display | 2 → 2.75 | 32 sp | Big numbers (tabular) |
| headline | 1.5 → 1.875 | 26 sp | Page titles |
| title | 1.1875 → 1.375 | 20 sp | Section, dialog titles |
| title-sm | 1.0625 | 17 sp | List-row primary line |
| jp-body | 1.0625 → 1.1875, lh 1.9 | 18 sp | Japanese running text |
| reading | 1.0625 → 1.25 | 18 sp | Kana under a headword |
| body-lg | 1.0625 → 1.125 | 17 sp | Glosses |
| body | 1 | 16 sp | Default |
| body-sm | 0.875 | 14 sp | Secondary lines, cells |
| label | 0.875 / 500 | 14 sp | Buttons, tabs, chips |
| caption | 0.8125 | 12 sp | Metadata, intervals, key hints (smallest size) |
| overline | 0.75 / 600, uppercase | 12 sp | Latin eyebrows only, never Japanese |

- **Furigana:** `rt` = 0.6 × base, never below 12 px/sp (`--kb-ruby-scale`, `--kb-ruby-min`); text that carries
  ruby gets `line-height: 2.2` (`--kb-ruby-lh`) so readings never collide with the line above. `ruby-align: center`.
- **Kana line-height:** Japanese body 1.9 with 0.02em tracking; UI body 1.65.
- **Line breaking:** `lang="ja"` on all Japanese; `word-break: auto-phrase` on headwords and sentences
  (phrase-aware breaking in Chromium, including the Android WebView; other engines fall back to normal CJK
  breaking). Headwords get enough width to sit on one line at every size in the matrix; that is a screenshot
  check. `overflow-wrap: anywhere` only on Latin glosses and IDs.
- **200 % text:** everything is rem/sp, rows grow instead of clipping, multi-column groups reflow (grade bar goes
  2 × 2 under 24 rem of width, list-row trailing chips wrap under the text, toasts wrap their action). Android
  uses `lineHeight` in sp and nonlinear font scaling (API 34+) caps display sizes.

### 2.3 Space, shape, elevation, size

- Space: 4 px grid, `--kb-space-{0,0-5,1,2,3,4,5,6,8,10,12,16,20,24}` (0–96 px); Compose `KbSpace.s4` etc.
- Radius: xs 4, sm 8 (inputs), md 12 (buttons, rows, chips' container), lg 16 (cards, panels), xl 24 (dialogs,
  sheets), full (pills). Compose `KbShapes` maps these to M3 shapes.
- Elevation: 0–3. Light uses soft warm shadows; dark relies on `surface-raised` tone plus a dark shadow.
  Panels default to elevation 0 + hairline; only the review card (1), menus (2) and dialogs/sheets (3) lift.
- Sizes: touch target 44 px / 48 dp minimum (`touch-min`), control height 44 px / 48 dp, grade buttons 64 px /
  72 dp, top bar 56/64, bottom nav 64/80, sidebar 248, rail 88, reading measure 680 px, review card 720 px max,
  content 1200 px max.
- Breakpoints (web px = Android dp, min-width): compact < 600, medium 600, expanded 840, large 1024, xlarge 1440.

### 2.4 Motion

| Token | ms | Use |
|---|---|---|
| instant | 80 | Press scale (0.98) |
| fast | 140 | Hover, colour changes, segmented thumb |
| base | 200 | Reveal, dialog rise, tab underline |
| slow | 240 | Progress fill, page-level fades |

Easing: standard (0.2, 0, 0, 1), decelerate, accelerate (exits), emphasized (0.05, 0.7, 0.1, 1) for the reveal.
Distances `--kb-distance-{sm,md,lg}` = 4/8/16 px become 0 under `prefers-reduced-motion` (Android: animator
duration scale 0 → fades only).

### 2.5 Theme

Light and dark from day one. Default follows the system. Manual override: Settings › Appearance (System /
Light / Dark segmented control).

- Web: `web/src/lib/ui/theme.svelte.ts` stores the choice in `localStorage['kb-theme']` and sets
  `data-theme` on `<html>`. UI-WEB adds a 3-line inline script in `app.html` that applies the stored value
  before first paint (no flash). Any element may carry `data-theme` (the styleguide shows both themes at once).
- Card stylesheet: `assets/card/card.css` has its own `--kb-card-*` variables (generated), honours
  `prefers-color-scheme` and `data-theme` on any ancestor. The Android review WebView must set
  `data-theme` on the card page's `<html>` from the app theme (UI-APP), because the WebView's media query
  follows the system, not the in-app override.
- Android: `KotobayaTheme(dark = …)` (UI-APP) picks `KbColorsLight/Dark`, provides them through a
  `CompositionLocal`, and wraps `MaterialTheme(colorScheme = colors.toColorScheme(dark), typography =
  kbTypography(...), shapes = KbShapes)`. No dynamic colour (Material You) — the palette is the brand.

## 3. Components

Web primitives live in `web/src/lib/ui/`; Compose equivalents are built by UI-APP with the same anatomy and
tokens. All interactive components: 44 px / 48 dp hit area, visible focus ring (2 px `focus`, 2 px offset),
hover = `surface-hover` wash, press = scale 0.98 at `instant`.

| Component | Variants / anatomy | Rules |
|---|---|---|
| **Button** (`Button.svelte`) | primary · tonal · outline · quiet · danger; sm (desktop toolbars, still 44 px hit area) · md · lg; leading/trailing icon; loading (spinner, `aria-busy`); link form (`href`) | One primary per view. Labels are verbs ("Start review", "Delete deck"). Disabled = sunken fill + disabled text, never just opacity. |
| **Icon button** | quiet · tonal · outline; toggle (`aria-pressed`) | Always an accessible label (also the tooltip). |
| **Card surface** (`Surface.svelte`) | surface · sunken · raised; padding sm/md/lg; elevation 0–3 | Panels on the canvas; sunken wells for mnemonics and code. |
| **List row** (`ListRow.svelte`) | title (+`lang`), supporting (2-line clamp), meta, leading (dot, icon, avatar), trailing (chip, switch); link (chevron), button, static; selected (indigo bar + tint) | Min 56 px; wraps trailing under the text at large text sizes. The unit of every list on both platforms. |
| **Segmented control** | icon + label options, `full` | Radio-group semantics; arrows move. For 2–4 peer views (Cards/Notes, Today/Week/Month/Year, theme). |
| **Tabs** | label + optional count | Underline indicator, scrolls horizontally if needed. For sections of one object (dictionary entry). |
| **Chip** | filter (toggle, check glyph when on) · state (colour + glyph + word) · deck (dot + name) · tag (removable) | 32 px visual / 44 px hit area. |
| **Input** (`TextField.svelte`) | label (visible, or hidden for search with an icon), hint, error (icon + text, `aria-invalid`), clearable, disabled | 44 px; 1 px `border-strong`, focus → indigo ring. Errors under the field, never only a red border. |
| **Dialog / sheet** (`Dialog.svelte`) | title, body, actions; centred dialog ≥ 600 px, bottom sheet < 600 px (or forced) with grab handle | Native `<dialog>`; Esc and backdrop close; actions right-aligned (stacked full-width in sheets), destructive action last and `danger`. |
| **Toast** (`Toast.svelte`) | info · success · warning · error; one action (Undo); dismiss | Bottom-right on desktop, above the bottom bar on phones; errors stay until dismissed, others 5 s. Max one action. |
| **Empty state** (`EmptyState.svelte`) | icon disc or the ensō; title, one-line body, one action; `page` or `inline`; `error` tone with Retry | Every list has one. The ensō is reserved for "review finished". |
| **Loading** (`Skeleton.svelte`, `Spinner.svelte`) | text lines, list rows, block of a given height | Skeletons reserve the real layout (no shift). Spinners only inside buttons and for < 1 s waits. Pulse stops under reduced motion. |
| **Progress** (`ProgressBar.svelte`) | 4 px track, optional "12 of 85" | Review progress, import jobs. |
| **Grade bar** (`GradeBar.svelte`) | 4 buttons: name (grade colour), interval, key hint | Tonal container fills with a 3 px top rule in the grade colour; Good (the default) is fully outlined. Key hints only on keyboard devices with room. 2 × 2 when narrow. |
| **Pitch accent** (`PitchAccent.svelte`) | morae with overline + drop tick; pattern number + name | `pitchShape()` in `kinds.ts`; morae come from the state layer. |
| **Kbd** | key cap | Key hints in review and tooltips. |
| **Data table** (web, `DataTable.svelte`) | columns (align, width, sortable, `lang`, optional), cell snippet, sort, row selection (click/Enter), comfortable/compact, loading and empty states | Sticky sunken header; numbers right-aligned tabular; scrolls inside its own box; optional columns drop under 600 px of table width. Phones use list rows instead. |
| **App shell** (`AppShell.svelte`) | sidebar ≥ 1024 · rail 600–1023 · top bar + bottom nav < 600 with "More" sheet | Container queries (adapts to its box). Brand seal 言 in vermilion. |

## 4. Information architecture

### 4.1 Web (app shell)

Sidebar order (rail shows the same icons; the phone bottom bar shows the first four + More):

1. **Today** `/` — dashboard
2. **Review** `/review`
3. **Dictionary** `/dictionary` — words and kanji
4. **Browse** `/cards` ⇄ `/notes` — one destination, Cards | Notes segmented control
5. **Decks** `/decks`
6. **Struggle** `/struggle` — leeches, confusables, hot words
7. **Stats** `/stats`
8. **Reading** `/media` — media library, reader, coverage (web-only)

Secondary (bottom of the sidebar; inside More on phones): **Import** `/import`, **Note types** `/note-types`,
**Settings** `/settings` (account, devices, appearance, study, attribution). Sync status and log-out sit in the
sidebar footer / phone top bar. Login has no shell.

### 4.2 Android

Bottom navigation (phone), navigation rail (foldable / ≥ 600 dp), rail + list–detail panes (tablet ≥ 840 dp):

1. **Today** — due counts, Start review, struggle and sync status
2. **Review** — full-screen; the tab opens it directly when cards are due, otherwise shows the "all done" state
3. **Search** — dictionary (words, kanji) and your cards in one search
4. **Decks**
5. **Settings**

**Media tab removed (I-5).** Media import and the reader are web/desktop tasks. The audio prefetch and storage
options it held move to Settings › Audio & offline. Struggle is reached from a Today card and its deep link
(notifications/widget), not a tab. Grammar capture and the share-intake sheet are launched from the system
share menu and from Search, not from the tab bar.

## 5. Visual plan per screen

Acceptance for every screen: the B1 screenshot matrix (web 360/768/1280/1920 × light/dark × text 1.0/2.0; Android
phone/foldable/tablet × light/dark × font 1.0/2.0) shows no horizontal scroll, no clipped or overlapping text, no
tap target under 44 px / 48 dp, visible focus, AA contrast, designed empty/loading/error states, no layout shift,
Japanese only in the Japanese face, motion under 250 ms.

### 5.1 Review (web `/review`, drill `/review?drill=…`; Android Review)  ← first

Layout:
- **Header** (slim, 56 px): deck chip (deck colour dot + name; tap = switch deck/queue), progress bar with "12 of 85",
  overflow menu (undo, edit note, suspend, bury, flag, open in dictionary). On desktop the app sidebar collapses
  to the rail while reviewing; on phones the bottom bar hides (Review is immersive; back/Esc returns to Today).
- **Card** (`surface`, radius lg, elevation 1) centred, max 720 px wide, filling the height between header and
  actions. Front content is vertically centred; the back pushes content up from the same centre line so the
  headword does not move (front stays where it was, back fades/slides in below it — no layout jump). Long backs
  scroll inside the card; the actions never move.
- **Actions** in the thumb zone: before reveal one full-width **Show answer** (lg, primary) with a `Space` key
  hint; after reveal the **grade bar** in exactly the same place and height (64 px / 72 dp), four equal buttons
  with intervals; 2 × 2 at narrow/large text. Desktop: key hints 1–4, Space, U (undo), E (edit), plus `?` opens a
  shortcut sheet.
- Widths: phone — card edge-to-edge with 12 px gutter, actions pinned to the bottom with safe-area padding.
  Tablet/foldable — card 640 dp centred, grade bar under it, same width. Desktop ≥ 1024 — card 720 px, actions
  under the card (not at window bottom), keyboard-first. 1920 — same, more canvas.

Hierarchy: headword (jp-display) ▸ reading + pitch (reading) ▸ gloss (body-lg) ▸ example (jp-body with spot
highlight) ▸ translation (muted) ▸ notes/mnemonic (sunken well).

Motion — the reveal: back content fades in and rises 8 px over 200 ms (emphasized); the divider draws from the
centre out (140 ms); Show answer cross-fades into the grade bar. Grading: the card slides 16 px left and fades
(140 ms, accelerate), the next card fades in (200 ms). Reduced motion: fades only. Audio autoplay respects the
setting; the play button pulses once (not in reduced motion).

Progress that motivates without nagging: progress bar + count only; at 25/50/75 % nothing pops. At the end the
**finished state**: the vermilion ensō, "All done for today", when the next cards are due, and quiet links
(Study ahead, Browse leeches). No streak shaming, no confetti.

States: loading = skeleton card of the same size + disabled actions (no shift); empty (nothing due) = finished
state; error loading the queue = inline error in the card with Retry and "Review offline" on Android; offline =
warning banner under the header ("Reviews are kept and will sync"); 900 due = header shows "900 due · 85 in this
session" and a one-line tip to raise the session limit — never a wall of red.

Drill mode (leeches/confusables): same layout, header chip says "Drill: Leeches", grades show no intervals
(drill doesn't reschedule) and the finished state links back to Struggle.

### 5.2 Today / home (web `/`, Android Today)

- **Hero panel**: due now (display figure), new available, estimated minutes, and **Start review** (lg primary,
  full-width on phones). On Android this sits above the fold with the button in thumb reach.
- Below: **deck list** (list rows with deck dot, due/new counts right-aligned, chevron), **Struggle** card (top 3
  leeches as chips, link), **Activity** (14-day review heatmap strip + retention line, read-only), **Sync/status**
  row (last sync, device).
- Widths: one column < 840; two columns ≥ 840 (hero + decks left, struggle/activity right); max 1200.
- States: empty account = page empty state ("Add your first words": Import, Create deck, Search dictionary);
  loading = skeleton hero + 4 rows; nothing due = hero shows the ensō mini state and "next due at 04:00";
  error = inline error panel with Retry, cached numbers shown greyed with "as of 10:42".

### 5.3 Dictionary (web `/dictionary`; Android Search + word detail)

- Search field at the top (search icon, clearable, autofocus on desktop, `/` shortcut); results as list rows:
  headword (ja, title-sm), reading, first gloss (2-line clamp), in-deck chip (state chip) and JLPT/common tags.
- **Entry**: headword (jp-display) with furigana option, reading + pitch accent (component), audio button, add-to-deck
  (tonal) or the in-deck chips; tabs: Senses (count) · Kanji · Examples. Senses: numbered, part-of-speech eyebrow,
  glosses in body-lg, scannable spacing; misc tags as small chips. Kanji panel: each kanji as a tile (jp-hero
  scaled down, keyword, on/kun readings), click opens the kanji view (meanings, readings, strokes, words using it,
  in-deck markers).
- Widths: phone — results list, entry as a pushed page. Tablet — list–detail 40/60. Desktop — list 380 px +
  entry with reading measure (680 px).
- States: empty query = recent searches + "Try 取る, toru, take"; no results = empty state with romaji/kana tips;
  loading = row skeletons (no shift between keystrokes: keep the old results dimmed); dictionary not installed
  (Android) = empty state with Install; error = inline error.

### 5.4 Browse — Cards / Notes (web `/cards`, `/notes`; Android Search › Cards)

- Real **master–detail**. Toolbar: search (query syntax from core), filter chips (deck, state, due, has audio,
  leech), Cards | Notes segmented control, column density toggle.
- Master: data table (web ≥ 600) with Word, Reading, Deck, State chip, Due, Lapses; selection highlights the row.
  Phones: list rows.
- Detail pane (≥ 1024 side-by-side, 420–560 px; < 1024 a full-height sheet/page): the **actual card preview**
  (card.css in a surface, front/back toggle, light/dark follows the app), then fields (note editor), deck, tags,
  history mini-chart, actions (suspend, bury, reset, delete). FEAT-x WYSIWYG per-field override plugs into the
  preview (needs the core render contract first).
- States: empty account / no matches (different copy), loading table skeleton, preview error = card-shaped error
  with the message from core (I-3 must never take down the page).

### 5.5 Decks (web `/decks`, `/decks/new`, `/decks/[id]`; Android Decks)

- List of decks as rows (deck dot, name — long names wrap to 2 lines —, due / new / total, chevron), 30+ decks
  stay scannable: group by parent, sticky group headers, a filter field when > 12 decks.
- Deck detail: header with name, colour (picker of the nine deck colours with names), counts; tabs Overview ·
  Options · Cards. Options are grouped list rows with inline controls (new/day, review limit, FSRS retention
  slider with preview of workload). List decks show their ordered items with drag handles (web) / reorder mode.
- New deck: dialog (web ≥ 600) / sheet (phones): name, colour, type.
- States: empty = "No decks yet" with Create and Import; loading rows; error panel.

### 5.6 Struggle (web `/struggle`, `/struggle/leeches`, `/struggle/confusables`, `/struggle/word/[id]`; Android Struggle)

- Overview: three panels — Leeches (count, top items as chips, Drill button), Confusables (pairs shown side by side
  in ja, Drill), Hot words (sparkline of recent lapses). Word page: headword, lapse history timeline, the card,
  actions (rewrite mnemonic, suspend, drill).
- States: empty = "Nothing is giving you trouble" (inline empty with a check disc); loading; error.

### 5.7 Stats (web `/stats`; Android: Today › Activity opens a stats page)

- Range segmented control (Today · Week · Month · Year). Headline numbers row (reviews, retention, time,
  streak) in display type, tabular. Charts: reviews per day (stacked by state colours with a legend that also
  uses labels), retention line vs target, forecast (900-due day visible without clipping — y axis scales, bars
  get a label), interval distribution. Charts use `text-muted` axes and `border` grid lines so they read in both
  themes; tooltips are raised surfaces.
- States: empty = "Stats appear after your first reviews"; loading = chart-shaped skeleton blocks of the final
  height (no shift); per-chart error with Retry (I-1: one slow chart never blocks the others).

### 5.8 Reading / media (web only: `/media`, `/media/new`, `/media/[id]`, `/read`, `/sentences`)

- Library: grid of media cards (cover/initial, title (ja), coverage % ring, last read); New = form page with
  file drop zone. Coverage: known/learning/unknown bar (state colours + labels), word table with "add" toggles.
  Reader: centred reading measure (680 px), jp-body with ruby toggle, unknown words underlined dotted (not
  only coloured), tap/click opens a popover dictionary entry. Sentences: list rows with "mine" action.
- States: empty library, import job progress (progress bar + toast on completion), parse errors inline.

### 5.9 Import (web `/import`, `/import/mapping`)

- Stepper (Upload → Map → Review → Done). Mapping uses a data table with selects; counts per mapping outcome are
  shown as chips and nothing silently dropped is hidden (unmapped rows listed). Large jobs show progress and can
  be left (toast when done).

### 5.10 Note types (web `/note-types`, `/new`, `/[id]`)

- List rows; editor is a three-pane layout ≥ 1024 (fields | template code | live card preview), tabs on smaller
  screens. Preview uses the real card.css and both themes toggle.

### 5.11 Settings (web `/settings`, `/settings/attribution`; Android Settings)

- Grouped list rows: Appearance (theme segmented control), Study (day starts at, limits, FSRS), Sync & devices
  (paired devices with last-seen, revoke as danger with confirm dialog, pair new = QR on web / scanner on Android),
  Dictionaries (Android: installed, size, update), Audio & offline (Android: prefetch, storage used — moved from the
  Media tab), Integrations (Android: AnkiConnect, Takoboto intake), About and attribution.
- Two-column on desktop (section index left, panel right); single column with section pages on phones.

### 5.12 Login and pairing (web `/login`; Android first run)

- Centred panel (max 400 px) on the canvas with the seal, password + TOTP fields, one primary button; errors under
  the fields; rate-limit message with countdown. Android first run: welcome → pair with server (scan QR from web
  Settings) → first sync with a real progress bar and a "you can start reviewing while this runs" note (I-4/I-7).

### 5.13 Android-only surfaces

- **Grammar capture / share intake**: a bottom sheet over the source app: the shared text (jp-body), detected
  words as selectable chips, target deck, Add (primary). Never a full-screen jump.
- **Widget / quick-settings tile**: due count in display type on `surface`, deck dot, Start review; follows the
  system theme (widgets cannot use the in-app override).
- **Notifications**: plain text with due count; colour only on the small icon.

## 6. Handoff notes

- UI-WEB: replace the POC layout with `AppShell`, import `lib/ui/fonts`, add the theme pre-paint script to
  `app.html`, build screens only from `lib/ui` primitives and `--kb-*` tokens; then delete
  `design/tokens/web-legacy.css` (and run `make tokens`) once no screen uses the legacy `--kb-bg`/`.kb-btn` names.
- UI-APP: wire `KotobayaTheme` to `Tokens.kt`, bundle the fonts, build the Compose components to section 3, set
  `data-theme` in the review WebView page, remove the Media tab.
- card.css follow-ups (need an owner; outside UI-DS's "tokens and dark mode only"): `.kb-audio` is 32 px (below
  the 44 px target), and `.kb-expression`/`.kb-sentence` should get `word-break: auto-phrase`.
