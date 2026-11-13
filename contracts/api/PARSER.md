# Parser contract: tokens, classes, overrides, worker parse service (C-12)

Frozen interface. Producer: the worker's parse pipeline (SPEC "Parsing" steps 1-5; tickets
T403a/b/c). Consumers: the server (`POST /parse`, coverage, i+1, sentence lists, the media-publish
runner), the web app (segmentation display and the two-click fix), devices that install media
content snapshots. Where code differs from this document, this document wins.

Key words MUST / MUST NOT / SHOULD / MAY are normative.

| File | What |
|---|---|
| `PARSER.md` | This document. |
| `token.schema.json` | JSON Schema (2020-12) of one token element (section 1). |
| `worker-parse.yaml` | OpenAPI 3.1 of the cluster-internal worker parse service (section 5). |
| `examples/tokens.json` | Fixture sentence `s03` as stored tokens (section 1.4). |
| `examples/parser-cases.json` | Golden vectors: class table, `pos_class` tie-break, override application. |
| `check_parser.py` | Validates the above (schema + cross-token rules + a reference implementation of sections 2-4 run over the vectors) and the token arrays in `contracts/db/media/examples/`. Run by `check.sh`. |

Owners of neighbouring formats: `app.sentence.tokens` storage (`contracts/db/V1__init.sql`; the
column comment's example is illustrative, this document is normative), snapshot archives
(`contracts/db/SNAPSHOT.md`, C-11), the public `POST /parse` (`openapi.yaml` `parseText`).

## 1. The token element (token format 1)

`app.sentence.tokens`, the `tokens` member of `sentence` snapshot records, `Sentence.tokens` and
`ParsedSentence.tokens` in the API, and the worker parse response all carry the **same** array of
token objects, byte-for-byte the same values (the API passes them through unchanged).

### 1.1 Members

Producers (the worker) MUST write all ten members, nothing else. Readers MUST ignore unknown
members (a later revision may add optional ones, see 6).

| Member | Type | Meaning and constraints | Example |
|---|---|---|---|
| `s` | integer ≥ 0 | Start offset, **UTF-16 code units** into the sentence's ORIGINAL text (`app.sentence.text`, `ParsedSentence.text`; never the normalised text), inclusive. | `9` |
| `e` | integer > `s` | End offset, exclusive. `e ≤` UTF-16 length of the text. | `10` |
| `surface` | string, ≥ 1 code point | Exactly the original text's code units `[s, e)`. Never normalised (a half-width `ｶﾅ` stays half-width here). | `"見"` |
| `lemma` | string, ≥ 1 code point | Dictionary form used for matching (deinflected; from the NORMALISED text, so `１０` has lemma `10`). | `"見る"` |
| `reading` | string, may be `""` | Reading of the surface in **hiragana**: Sudachi's `reading_form` with katakana U+30A1–U+30F6 shifted by −0x60; `ー` and every other character kept. `""` only when no reading is known (override tokens, 4.4). | `"み"` |
| `pos` | array of 0-4 strings | Sudachi part-of-speech levels 1-4 in order, with `"*"` entries removed (`["動詞","一般"]`). Empty only for override tokens with no derivable POS (4.4). No element is `"*"` or `""`. | `["動詞","一般"]` |
| `cform` | string or null | Sudachi's conjugation form (POS field 6) **verbatim**, e.g. `"連用形-一般"`; null when Sudachi says `"*"` (the word does not inflect). Consumers that want the bare form (the grammar pattern language's `未然形`) take the part before the first `-`. | `"連用形-一般"` |
| `seq` | integer 1 … 2^53−1, or null | Matched JMdict `ent_seq` (soft reference to `ref.dict_entry.seq`). null = **unmapped** (no entry, a name with no entry, a number, or an override forcing "no entry"). | `1259290` |
| `class` | `content` \| `particle` \| `auxiliary` \| `name` \| `other` | Coverage filter class (section 2). Same value set as `app.occurrence.pos_class`. | `"content"` |
| `override` | boolean | true iff a parse override (section 4) produced this token. | `false` |

### 1.2 Array invariants (checked by `check_parser.py` and `contracts/db/media/validate.py` 3.3.4)

1. Ascending and non-overlapping: for consecutive tokens `a`, `b`: `a.e ≤ b.s`.
2. `0 ≤ s < e ≤ len16(text)`; neither `s` nor `e` falls between the two halves of a surrogate
   pair (𠮷 U+20BB7 is two code units, and a token holds both or neither).
3. `surface` = `text[s:e]` in UTF-16 terms.
4. **Gaps are untokenised text.** Every code unit not covered by a token is punctuation, a symbol
   Sudachi tags `補助記号`, or whitespace (Sudachi `空白`, or a morpheme whose surface is all
   Unicode `White_Space`). Producers MUST NOT emit tokens for those, except when an override
   explicitly covers them (4.4). Hence `len(tokens)` is the sentence's contribution to
   `media_segment.token_count`, and the number of tokens with `seq = null` its contribution to
   `unmapped_token_count`.
5. The array may be empty (`"……"`, `"！？"`): a sentence with no word tokens.
6. Tokens of sentences with `origin = 'generated'` follow the same rules (they are excluded from
   coverage by `origin`, not by their tokens).

### 1.3 Things that are NOT in a token

No sentence id, no offsets into the whole media, no normalised surface, no conjugation type
(Sudachi field 5), no entry metadata (status, gloss: `POST /parse` adds those as `entries`). A
token never stores a word status.

### 1.4 Example

Fixture `fixtures/parse/sentences.json` `s03`, `昨日、友達と映画を見ました。` (UTF-16 length
14; `、` at 2 and `。` at 13 are gaps). Full array: `examples/tokens.json`. Three of the eight
tokens:

```json
[{"s":0,"e":2,"surface":"昨日","lemma":"昨日","reading":"きのう","pos":["名詞","普通名詞","副詞可能"],"cform":null,"seq":1579260,"class":"content","override":false},
 {"s":9,"e":10,"surface":"見","lemma":"見る","reading":"み","pos":["動詞","一般"],"cform":"連用形-一般","seq":1259290,"class":"content","override":false},
 {"s":10,"e":12,"surface":"まし","lemma":"ます","reading":"まし","pos":["助動詞"],"cform":"連用形-一般","seq":2210290,"class":"auxiliary","override":false}]
```

## 2. `class` derivation

Inputs: `P` = the token's Sudachi POS (the full six fields before trimming; for override tokens
the derived `pos` of 4.4, possibly empty); `E` = `ref.dict_entry.pos` of `seq` in the dictionary
release the parse used (the union of JMdict POS tags over all senses, e.g. `{v1,vt}`; the empty
set when `seq` is null or the entry is not in the release). `P0`, `P1` are POS levels 1 and 2.

The **first** matching row wins:

| # | Condition | `class` | Why |
|---|---|---|---|
| 1 | `P0 = 助詞` | `particle` | は が を に で と から まで よ ね … |
| 2 | `P0 = 助動詞` | `auxiliary` | ます た ない れる せる だ です … |
| 3 | `P0 = 名詞` and `P1 = 固有名詞` | `name` | person, place, organisation names, mapped or not |
| 4 | `E ≠ ∅` and `E ⊆ {n-pr}` | `name` | JMdict entries that are only proper nouns |
| 5 | `E ≠ ∅` and `E ⊆ {prt}` | `particle` | Sudachi called it something else (e.g. a 接続詞 use) but JMdict knows only a particle |
| 6 | `E ≠ ∅` and `E ⊆ {aux, aux-v, aux-adj}` | `auxiliary` | as 5, for auxiliaries |
| 7 | `P0 = 名詞` and `P1 = 数詞` | `other` | numerals and digit strings (`3`, `１５`, `三`); never hidden, kept apart for display |
| 8 | `P0 ∈ {名詞, 代名詞, 動詞, 形容詞, 形状詞, 副詞, 連体詞, 接続詞, 感動詞, 接頭辞, 接尾辞}` | `content` | ordinary vocabulary, including conjunctions, interjections and affixes (さん, 的, 枚) |
| 9 | `P` is empty and `seq` is not null | `content` | override token mapped to an entry, no POS derivable |
| 10 | anything else (`記号`, `フィラー`, `その他`, an empty `P` with null `seq`, a POS a future Sudachi dictionary adds) | `other` | |

Sudachi fills only trailing POS levels with `"*"`, so evaluating the table on the trimmed `pos`
of 1.1 gives the same result (a missing `P1` matches nothing). Rules 4-6 only refine; they never turn a `助詞`/`助動詞`/proper-noun token into something else
(rows 1-3 come first). Override tokens with an explicit `class` skip this table (4.4).

What the classes drive (unchanged, restated so implementers agree):

- `CoverageHide` `names` / `particles` / `auxiliary` hide **entries** by `occurrence.pos_class`
  (2.1), not tokens.
- `SentenceFilter` i+1 / i+0 count, per sentence, the distinct non-null `seq`s whose effective
  status is `new` among tokens whose `class` is `content` or `other`; tokens of class `particle`,
  `auxiliary`, `name` and unmapped tokens never count. (Token-level: in one sentence `から` as a
  particle is not counted even if the same entry is `content` elsewhere.)
- `other` is never hidden by any filter; it differs from `content` only for display.

### 2.1 `occurrence.pos_class` (most frequent use, tie-break)

For a segment and an entry `seq`: over the tokens with that `seq` in the segment's sentences
with `origin = 'media'`, count tokens per `class`. `pos_class` = the class with the highest count;
ties go to the class that comes **first** in the fixed order

`content` > `name` > `particle` > `auxiliary` > `other`.

Rationale: on a tie prefer the class no filter hides (content), so an ambiguous word is never
silently dropped from coverage. jpdb-imported occurrences (no sentences) are `content` unless the
jpdb importer has POS evidence of its own (its business; this rule applies only where tokens
exist). Example: 2 × `content` + 2 × `particle` → `content`; 1 × `name` + 1 × `other` → `name`;
3 × `particle` + 1 × `content` → `particle`.

## 3. Pipeline order (where tokens come from)

Per sentence of a media, and per sentence of a `POST /parse` text:

1. Normalise (width, iteration marks, long-vowel look-alikes) with an offset map back to the
   original UTF-16 code units. Sudachi never sees the original; tokens never show the normalised
   text except in `lemma`.
2. Segment with Sudachi, mode C; a mode C morpheme whose lemma and surface are both unknown to
   JMdict is re-split with mode B (then A). Drop non-word morphemes (1.2.4).
3. Deinflect; match JMdict (`seq`, ranking by reading, POS, frequency); derive `class` (2).
4. Apply parse overrides (4).
5. Aggregate (media only): `occurrence` rows, `token_count`, `unmapped_token_count`
   (`SNAPSHOT.md` 3.3.7, 2.1 above).

Steps 1-3 are a pure function of (sentence text, Sudachi dictionary, JMdict release, worker
code); step 4 adds the override rows. `parser_version` (6) names everything except the text and
the overrides.

Sentence splitting for `POST /parse` is the worker's paste/TXT splitter
(`kotobaya_worker.extract.split.split_sentences`): a run of `。！？!?…‥．` ends a sentence and
absorbs directly following closing brackets; nothing splits inside an open bracket pair
(`「」『』（）()【】［］[]〈〉《》〔〕｛｝{}`) except that a pair closing right after a terminator ends
the sentence when followed by an opener, whitespace or the end of the line; a newline always
ends a sentence and resets the bracket depth; leading and trailing ` \t\r` and U+3000 are
trimmed; empty sentences are dropped. Each `ParsedSentence.text` is therefore a contiguous
substring of the request text, and sentences are in input order. Input of only whitespace yields
`sentences: []`.

## 4. Applying parse overrides (step 4)

### 4.1 Which overrides apply

Only live rows (`deleted_at IS NULL`). For a sentence with id `S` of media `M`:

| Scope | Row | Rank |
|---|---|---|
| sentence | `media_id = M AND sentence_id = S` | 0 (most specific) |
| media | `media_id = M AND sentence_id IS NULL` | 1 |
| global | `media_id IS NULL` (then `sentence_id` is null too) | 2 |

A row whose `sentence_id` is set but whose `media_id` is not `M` does not apply. `POST /parse`
(no media, no sentence ids) applies **global overrides only**. A media parse reads the override
rows in the same database snapshot as its base version (`MEDIA_PIPELINE.md`, `REPEATABLE
READ`); an override changed after that takes effect at the next parse. Sentence-scoped overrides
apply after sentence ids are assigned (`SNAPSHOT.md` 3.4): a sentence whose text changed has a
new id, so its old sentence-scoped overrides stop applying (they stay stored, soft reference).

A stored row whose token surfaces do not concatenate to `match_text` (impossible through the API
or op apply, which reject it) is skipped and logged, never an error for the parse.

### 4.2 Matching

`match_text` is compared with the sentence's **original** text, exact UTF-16 code-unit equality
(no normalisation, no case or width folding), because it is taken from token surfaces, which are
original text. A **candidate** is every `(override, i)` such that `text[i : i + len16(match_text)]
= match_text` (all occurrences, overlapping ones included) **and** the span `[i, j)` is
**aligned**: no base token `t` (the tokens of step 3) has `t.s < i < t.e` or `t.s < j < t.e`. A
span may start or end inside a gap and may cover gaps and several whole base tokens. A
misaligned occurrence is not a candidate (an override never cuts a token it does not fully
cover).

### 4.3 Selection (overlaps)

Sort all candidates by the key

`(rank ascending, len16(match_text) descending, hlc descending, id ascending, i ascending)`

(`hlc` compared byte-wise, `SCHEMA.md` 3; `id` as the lowercase UUID string). Walk the sorted
list and **accept** a candidate iff its span `[i, j)` shares no code unit with an already
accepted span. Accepted spans are applied; the rest are dropped. Consequences: a sentence-scoped
override beats any overlapping media or global one; within a scope the longest `match_text` wins,
then the newest; repeated or self-overlapping occurrences of one override are taken
leftmost-first (`ああ` over `あああ` accepts `[0,2)` only).

### 4.4 Building the replacement tokens

For an accepted span `[i, j)`: remove every base token inside it, then insert one token per
override token, in order, at consecutive offsets starting at `i` (each `e` = its `s` + len16 of
its surface; the last `e` = `j`). A replacement token whose surface is entirely Unicode
`White_Space` is not emitted (it stays a gap); every other one is emitted even if it is
punctuation (the user covered it on purpose; it then counts as a word token). Members:

| Member | Value |
|---|---|
| `s`, `e`, `surface` | as above (`surface` = the override token's `surface` = `text[s:e]`). |
| `seq` | The override token's `seq`; **absent is treated as null**. null = forced "no entry". Never re-matched. |
| `lemma` | The override token's `lemma` if present; else the lemma of a base token with exactly the same `[s, e)`; else `surface`. |
| `reading`, `pos`, `cform` | `reading` given in the override token wins for `reading`. Otherwise, in order: (a) a base token with exactly the same `[s, e)` → copy its `reading`, `pos`, `cform`; (b) else analyse `surface` alone (normalise + Sudachi mode C, no fallback, no override): if that yields exactly one morpheme and it is a word morpheme (not `補助記号`/`空白`), take its `reading`, `pos`, `cform` as in 1.1; (c) else `reading` = concatenation of the hiragana readings of all morphemes of (b), `pos = []`, `cform = null`. |
| `class` | The override token's `class` if present (one of the five values); else section 2 with the `pos` just derived (for (a)/(b) the morpheme's full POS) and `E` of the override `seq`. |
| `override` | `true`. |

Base tokens outside accepted spans are unchanged (`override: false`). The result satisfies 1.2
(alignment guarantees no overlap). Occurrences (step 5) are computed from the final tokens, so a
`seq: null` override removes the word from occurrences and adds to `unmapped_token_count`.

Override token members (`ParseOverridePut.tokens[]`, `app.parse_override.tokens`): `surface`
(required), `lemma`, `seq`, and, from C-12, optional `reading` (hiragana) and `class`. Unknown
members are preserved in storage (`OPS.md`) and ignored by the parser.

### 4.5 Worked example

Base tokens of `食べかけのパンだ。` (step 3): `食べかけ[0,4) seq 1358290 content`, `の[4,5)
particle`, `パン[5,7)`, `だ[7,8) auxiliary`; `。` is a gap. Overrides:
G = global `食べかけ` → `[{食べ, 食べる, 1358280}, {かけ, かける, 1207610}]`;
M = media `かけの` → `[{かけの, seq null}]`.
M's only occurrence `[2,5)` is misaligned (it cuts `食べかけ`), so it is not a candidate; G is
accepted at `[0,4)`. Output: `食べ[0,2) 1358280 override`, `かけ[2,4) 1207610 override`, then
`の`, `パン`, `だ` unchanged. `食べ` and `かけ` take reading/pos/cform from analysing their
surfaces alone (4.4 b). More cases, including sentence scope beating a longer global override and
a surrogate pair: `examples/parser-cases.json`.

## 5. Worker parse service (`POST /parse` back end)

The server answers `POST /parse` synchronously by calling the worker. Machine-readable:
`worker-parse.yaml`.

### 5.1 Transport and deployment

- HTTP/1.1, JSON (UTF-8), cluster-internal. The worker process serves it on its own port,
  `PARSER_PORT` (default **8082**), bound to `PARSER_BIND` (default `0.0.0.0`). The existing
  liveness endpoint (`GET /healthz` on 8081) stays separate so a busy parser never fails
  liveness.
- Paths: `POST /internal/parse`, `GET /internal/health`. Nothing else; any other path is `404`.
- **No authentication.** Access control is the network: the Helm chart's NetworkPolicy MUST
  allow ingress to `PARSER_PORT` only from the `api` pods, the port is exposed only through a
  ClusterIP Service, and the Ingress MUST NOT route to it. Rationale: the endpoint is read-only
  (stores nothing, writes no log entry with user text at info level) and returns only dictionary
  data and global overrides. Compose: the port is not published to the host.
- Requests are served concurrently with job processing (own threads; the Sudachi analyzer is
  thread-safe per `kotobaya_worker.parse.sudachi`). At most `PARSER_MAX_CONCURRENCY` (default
  4) parses run at once; a request beyond that is answered at once with `503` code `busy`
  (never queued).

### 5.2 Request and response

`POST /internal/parse`, `Content-Type: application/json`, optional `X-Request-Id` (logged):

```json
{"text": "パンを食べる。"}
```

`text`: string, 1 to 20,000 Unicode code points (the same limit as `ParseRequest.text`, JSON
Schema `maxLength` semantics), no U+0000. Body at most 256 KiB.

`200`:

```json
{"parser_version": "kp1;sudachi=full-20250825;dict=2026-11-01.1;worker=0.4.0",
 "sentences": [{"text": "パンを食べる。", "tokens": [ … tokens as section 1 … ]}]}
```

`sentences` in input order (3); `tokens` satisfy 1.2 relative to their sentence's `text`;
overrides per 4 (global only). Deterministic: the same text, `parser_version` and override rows
give the same body. The worker MUST reflect every global override committed before the request
arrived (read per request, or a cache revalidated per request).

Errors are `application/problem+json` `{type, title, status, code, detail}`:

| Status | `code` | When |
|---|---|---|
| 400 | `invalid_request` | Not JSON, wrong `Content-Type`, missing or non-string `text`, empty text, U+0000. |
| 413 | `payload_too_large` | `text` over 20,000 code points or body over 256 KiB. |
| 503 | `busy` | Concurrency limit reached. |
| 503 | `not_ready` | Sudachi dictionary or dictionary release not loaded yet, or the database is unreachable. |
| 500 | `internal` | Anything else. |

`GET /internal/health`: `200 {"status":"ready","parser_version":"…"}` once a parse can be
served; `503 {"status":"starting","parser_version":null}` before. Helm/compose use it as the
worker's **readiness** probe.

### 5.3 The server side (`parseText`)

- Env on the API: `PARSER_URL` = base URL of the worker parse service, e.g.
  `http://kotobaya-worker:8082` (the server appends `/internal/parse`); `PARSER_TIMEOUT_MS`
  (default 10000, total per request including connect; connect timeout 1000 ms).
- Order: authenticate (`read` scope) → validate `ParseRequest` (`422` as today; over 20,000 code
  points `413 payload_too_large`, checked before calling the worker) → call the worker once (no
  retry: the caller may retry; parse is side-effect free) → build `ParseResult`.
- `ParseResult.sentences` = the worker's `sentences` unchanged; `ParseResult.parser_version` =
  the worker's; `ParseResult.entries` = one `DictEntrySummary` (with the caller's `status` and
  `note_id`) per distinct non-null `seq` among all tokens, ascending by `seq`; a `seq` the
  server's current release does not hold is omitted from `entries` (its tokens keep it).
- Mapping to the public response: worker `200` → `200`; worker `413` → `413
  payload_too_large`; `PARSER_URL` unset, connection refused/reset, DNS failure, timeout, worker
  `5xx` (`busy`, `not_ready`, `internal`), a `400` from the worker, or a body that is not a valid
  response → **`503 unavailable`** (with `Retry-After: 5` for `busy`/`not_ready`). The server
  logs the worker status and `code`; the client never sees worker internals.
- `POST /parse` being unavailable never affects the API's own readiness.

## 6. Versioning

- **Token format 1** is this document. Adding an optional member is additive (readers ignore
  unknown members). Removing a member, changing a meaning, or changing offsets' unit is a new
  format and needs a snapshot `format_version` bump (`SNAPSHOT.md` 6) and an API change.
- **`parser_version`**: an opaque string (≤ 200 characters, printable ASCII) that MUST change
  whenever steps 1-3 can give different output for the same text: worker parse code, Sudachi
  dictionary (name and version), JMdict release. Consumers compare it for equality only.
  Recommended form `kp<n>;sudachi=<dict>-<version>;dict=<release id>;worker=<version>`. It is
  returned by the worker (`/internal/parse`, `/internal/health`) and in `ParseResult` (optional
  member), and is declared optional in `JobResultMedia`. Media snapshots do not carry it yet:
  adding an optional `parser_version` member to the content archive header (`SNAPSHOT.md` 6
  allows unknown header members for readers) and copying it into `JobResultMedia` is a follow-up
  for the snapshot owner; until then `JobResultMedia.parser_version` is absent. Re-parse
  decisions ("parsed with an older dictionary") build on it later; nothing re-parses
  automatically today.
