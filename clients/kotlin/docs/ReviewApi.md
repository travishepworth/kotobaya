# ReviewApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**answerReview**](ReviewApi.md#answerReview) | **POST** /review/answer | Grade a card. |
| [**createDrillSession**](ReviewApi.md#createDrillSession) | **POST** /drill/session | Build an extra-practice session that does not touch scheduling. |
| [**getReviewQueue**](ReviewApi.md#getReviewQueue) | **GET** /review/queue | The next cards to review, built by the shared core&#39;s queue builder. |
| [**undoReview**](ReviewApi.md#undoReview) | **POST** /review/undo | Undo a review. |


<a id="answerReview"></a>
# **answerReview**
> ReviewResult answerReview(reviewAnswerRequest)

Grade a card.

Server emits &#x60;review.logged&#x60; and recomputes the card&#39;s schedule cache with the shared core. &#x60;mode&#x60; decides the review type:  - &#x60;schedule&#x60; (default): &#x60;review_type&#x60; &#x3D; &#x60;learning&#x60; if the card&#39;s state is &#x60;new&#x60;,   &#x60;learning&#x60; or &#x60;relearning&#x60; at apply time, else &#x60;scheduled&#x60;. Feeds FSRS. Reviewing a   card that is not due yet is allowed (FSRS accounts for the short interval). - &#x60;drill&#x60;, &#x60;cram&#x60;: logged with that &#x60;review_type&#x60;; the schedule does not change.  Rules: - &#x60;rating&#x60; is the FSRS rating 1-4. &#x60;button&#x60;, when sent, must map to &#x60;rating&#x60;   (&#x60;again&#x60;, &#x60;nothing&#x60;, &#x60;something&#x60; → 1; &#x60;hard&#x60; → 2; &#x60;good&#x60;, &#x60;okay&#x60; → 3; &#x60;easy&#x60; → 4);   otherwise &#x60;422&#x60;. Clients in five-button mode MUST send &#x60;button&#x60;. - &#x60;reviewed_at&#x60; defaults to the server&#39;s now. Past values are accepted (late   submission) within these bounds, each &#x60;422 validation_failed&#x60; with   &#x60;errors[0].pointer &#x3D; /reviewed_at&#x60;:   - more than 5 minutes after the server&#39;s now (&#x60;code &#x3D; in_future&#x60;);   - before &#x60;FLOOR&#x60; &#x3D; 2020-01-01T00:00:00Z (&#x60;code &#x3D; before_floor&#x60;);   - before &#x60;min(card.created_at, now) − 24 h&#x60; (&#x60;code &#x3D; before_card&#x60;), where &#x60;card&#x60; is the     card the review is logged on (the survivor, for a merge loser&#39;s id) and &#x60;created_at&#x60;     is &#x60;wall(hlc)&#x60; of the &#x60;card.created&#x60; op that inserted the row (SYNC.md 7.2, 7.5).   The op the server mints is exempt from the push-time clock checks (SYNC.md 3.4), so   these are the only bounds a REST review gets; they reproduce the device guards   (G1, and G2&#39;s implied &#x60;reviewed_at &gt;&#x3D; card.created_at − 24 h&#x60;) and the lower bound   Re-mint uses (SYNC.md 10.4 step 4). The &#x60;min&#x60; with now keeps a card created by a   device up to 24 h ahead reviewable at once. The bound uses the replicated   &#x60;created_at&#x60;, which comes from the creating device&#39;s clock; SYNC.md 3.4 records   that a pushed review is not checked against it (known issue) - this REST check does   not change that. - &#x60;utc_offset_min&#x60; SHOULD be sent by browsers (&#x60;-new Date().getTimezoneOffset()&#x60;); when   absent it is stored as NULL. - &#x60;duration_ms&#x60; above 600000 is clamped to 600000. - &#x60;id&#x60; is **required**: the client-generated UUIDv7 &#x60;review_log.id&#x60;, minted once when the   answer is given and reused unchanged on every retry. Re-sending an existing &#x60;id&#x60; returns   the stored review and the card&#39;s current state with &#x60;200&#x60; and emits nothing - whatever   the rest of the body. This is what makes a retry after a lost response or a &#x60;5xx&#x60; safe   (no second &#x60;review.logged&#x60;, no double FSRS update). An &#x60;id&#x60; that exists as a review of a   different card is still answered with the stored review (the body is ignored). - Card suspended and &#x60;mode &#x3D; schedule&#x60;: &#x60;409 card_not_reviewable&#x60;. Buried cards may be   answered. Deleted card or note: &#x60;410 gone&#x60;. A merge loser&#39;s id is answered against the   survivor (&#x60;review.card_id&#x60; in the response is the survivor&#39;s id). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ReviewApi()
val reviewAnswerRequest : ReviewAnswerRequest = {"card_id":"01931f40-1a2b-7c3d-8e4f-000000000c01","id":"01931f41-0000-7000-8000-000000000f03","mode":"drill","rating":3} // ReviewAnswerRequest | 
try {
    val result : ReviewResult = apiInstance.answerReview(reviewAnswerRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ReviewApi#answerReview")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ReviewApi#answerReview")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **reviewAnswerRequest** | [**ReviewAnswerRequest**](ReviewAnswerRequest.md)|  | |

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="createDrillSession"></a>
# **createDrillSession**
> DrillSession createDrillSession(drillSessionRequest)

Build an extra-practice session that does not touch scheduling.

Returns an ordered list of cards to drill. Nothing is stored and no op is emitted: the session exists only in the response (&#x60;id&#x60; is a fresh UUIDv7 for client-side bookkeeping). The client shows the items and posts each answer to &#x60;POST /review/answer&#x60; with &#x60;mode&#x60; &#x3D; the session&#39;s &#x60;mode&#x60; (&#x60;drill&#x60; or &#x60;cram&#x60;).  &#x60;source&#x60; selects the cards: - &#x60;hot_words&#x60;: live cards of the current hot words, hottest first. - &#x60;leeches&#x60;: live cards with &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;, most lapses first. - &#x60;confusables&#x60;: cards of confirmed confusable pairs, partners adjacent. With   &#x60;entry_seq&#x60;, only pairs involving that entry&#39;s note. - &#x60;query&#x60;: cards matching &#x60;query&#x60; (card search string; required), in &#x60;-lapses&#x60; order. - &#x60;cards&#x60;: exactly &#x60;card_ids&#x60; (required, 1 to 500), in the given order; unknown, deleted   and merged-away ids are dropped silently. Suspended cards are included (drilling a suspended leech is the point); deleted ones never. An empty &#x60;items&#x60; list is a normal &#x60;200&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ReviewApi()
val drillSessionRequest : DrillSessionRequest = {"limit":50,"mode":"cram","query":"deck:\"Frieren\"","source":"query"} // DrillSessionRequest | 
try {
    val result : DrillSession = apiInstance.createDrillSession(drillSessionRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ReviewApi#createDrillSession")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ReviewApi#createDrillSession")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **drillSessionRequest** | [**DrillSessionRequest**](DrillSessionRequest.md)|  | |

### Return type

[**DrillSession**](DrillSession.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="getReviewQueue"></a>
# **getReviewQueue**
> ReviewQueue getReviewQueue(limit, deckId, kinds, keepGoing)

The next cards to review, built by the shared core&#39;s queue builder.

For online clients (web, scripts). The server runs the same queue builder the phone runs locally, at request time, over the server&#39;s replica: daily limits (global and per deck), deck priority order, sibling burying and the proportional mix of reviews and new cards all come from the core (&#x60;ReviewLoop.evaluate&#x60;, ADR-037). The response is a prefix of today&#39;s queue: the first &#x60;limit&#x60; cards in the order they should be shown. It is **not** a reservation: fetch again after answering (each answer can change what comes next - learning steps, sibling burying, the mix). Suspended, buried, deleted and merged-away cards never appear. &#x60;loop&#x60; (ADR-037) carries the phase and every number a client shows; with an empty &#x60;items&#x60;, &#x60;loop.phase&#x60; alone says which end state this is (see \&quot;Review loop\&quot; below). &#x60;counts&#x60;, &#x60;more_available&#x60; and &#x60;next_learning_due_at&#x60; are deprecated and kept with their old meaning for old clients.  **Review loop (ADR-037).** One rule, in the core (&#x60;ReviewLoop&#x60;), the same on the server and in the phone&#39;s local API. Vocabulary (the same numbers on every client at the same instant): - *ready learning*: learning/relearning cards whose step has elapsed (&#x60;due_at&#x60; &lt;&#x3D; now).   Never capped, never served early. - *ready reviews*: review cards due before &#x60;day_ends_at&#x60; (overdue included) within the   remaining review budget (&#x60;scheduler.reviews_per_day&#x60;, deck &#x60;reviews_per_day&#x60;; no   budget when &#x60;scheduler.reviews_unlimited&#x60;). - *ready now* &#x3D; ready learning + ready reviews. New cards are not part of it. - *later today*: learning/relearning cards due later, before &#x60;day_ends_at&#x60;. A hint only. - *backlog*: every due review an uncapped build would take; *beyond limit* &#x3D; backlog -   ready reviews. - *new today / new goal*: cards introduced today / &#x60;scheduler.new_per_day&#x60;.  Order of a normal request (&#x60;keep_going&#x3D;false&#x60;), always: ready learning first (a card that came due is the next card); then ready reviews (most overdue first) and new cards in the **proportional mix** (core &#x60;QueueBuilder&#x60; rule 5): new cards are spread evenly over the ready reviews that remain, so both run out at about the same time. The next card is a new card iff &#x60;s * gRem &gt;&#x3D; rRem&#x60;, with &#x60;rRem&#x60; &#x3D; ready reviews left, &#x60;gRem&#x60; &#x3D; new cards that can still be introduced today (the smaller of the goal&#39;s remainder and what the decks can supply under their limits) and &#x60;s&#x60; &#x3D; reviews answered since the last new card was introduced today; otherwise it is the most overdue review. So a fresh day starts with a review, there are never two new cards in a row while a review is left, and the order of one response equals what fetching after every answer gives. Both modes mix: with &#x60;scheduler.reviews_unlimited&#x60; &#x60;rRem&#x60; is every ready review, otherwise the reviews left under the cap. New cards per response are bounded by the day&#39;s remaining goal and by &#x60;limit&#x60; (the batch; an add_kanji group may overshoot &#x60;limit&#x60;, never the goal); &#x60;limit&#x60; does not change the order. Example: 4 reviews ready, 2 new cards left in the goal, nothing done today: &#x60;R1 R2 N1 R3 N2 R4&#x60;. Example: 200 ready, 40 left: a new card after every 5th review.  Phases (&#x60;loop.phase&#x60;; first match wins): 1. &#x60;reviewing&#x60;: the served queue has a card (or a deck entry to materialize, C-27). 2. &#x60;goal_reached&#x60; (only with &#x60;scheduler.reviews_unlimited&#x60; &#x3D; true) / &#x60;done_for_today&#x60;    (only with it false): only for &#x60;keep_going&#x3D;false&#x60;; nothing is ready under the limits    and &#x60;keep_going&#x3D;true&#x60; would return at least one card. Offer Keep going; it never    leads to a dead end. Which of the two is chosen by the setting, not by which limit    was hit. 3. &#x60;waiting&#x60;: nothing ready, keep going would return nothing (or is on and returned    nothing), and at least one card falls due later today:    &#x60;loop.later_today.next_due_at&#x60;. Happens only when no new card is left anywhere. 4. &#x60;empty&#x60;: nothing now, nothing with keep going, nothing later today. A learning card that falls due during 2-4 makes the next response &#x60;reviewing&#x60; again.  Client rules (normative for web, the shell SPA and the CLI): - **C1 Due first.** A client may hold a page of &#x60;items&#x60;, but before showing each next   card it MUST fetch again if &#x60;now &gt;&#x3D; T&#x60;, T &#x3D; the smallest of   &#x60;loop.later_today.next_due_at&#x60; of the last response (when not null) and &#x60;card.due_at&#x60;   of every &#x60;answerReview&#x60; response since then whose &#x60;card.state&#x60; is &#x60;learning&#x60; or   &#x60;relearning&#x60;. So a card that came due is the very next card. An undo   (&#x60;undoReview&#x60;) is followed by a fetch. - **C2 Keep going is persisted per client for the study day.** When the user starts it,   the client stores the &#x60;study_day&#x60; of the response it was started on (web and shell   SPA: &#x60;localStorage[\&quot;kotobaya.keepGoingDay\&quot;]&#x60;, value &#x3D; that &#x60;study_day&#x60; string; CLI   daemon: its state file; a count-only surface such as a widget needs none) and from   then on sends &#x60;keep_going&#x3D;true&#x60; on **every** &#x60;getReviewQueue&#x60; it makes (home, review,   status), also after leaving the review screen and after a restart. It is active iff   the stored value equals the &#x60;study_day&#x60; of the latest response; on a mismatch the   client deletes it and fetches again with &#x60;keep_going&#x3D;false&#x60;, discarding the   mismatching response. Per client, never synced: another browser or the phone keeps   its own state (off until started there). Not an op, not a setting. (Replaces   ADR-031&#39;s \&quot;one visit\&quot; scope.) - **C3 Display.** The headline \&quot;ready now\&quot; number is **always** &#x60;loop.ready.total&#x60;,   with or without keep going, so every client and surface (web, phone, widget, CLI)   shows the same number at the same instant. The backlog is shown only in a separate   backlog line, &#x60;loop.backlog.reviews&#x60;, displayed iff &#x60;loop.backlog.beyond_limit &gt; 0&#x60;,   in both modes (while keeping going past the cap the headline is therefore   &#x60;ready.learning&#x60; and the backlog line carries the reviews being cleared). A \&quot;later   today\&quot; hint only when &#x60;loop.later_today.count &gt; 0&#x60;; the new bar as &#x60;loop.new.today&#x60; /   &#x60;loop.new.goal&#x60; (it may exceed the goal after keep going: clamp the bar, show the   number). - **C4 Empty &#x60;items&#x60;** is rendered from &#x60;loop.phase&#x60; only. &#x60;waiting&#x60; schedules **one**   fetch at &#x60;next_due_at&#x60; + 1 s, and never sooner than 3 s after the previous   timer-driven fetch (the client&#39;s clock may differ from the server&#39;s; if the card is   still not served, the new response reschedules). New clients never read &#x60;counts&#x60;,   &#x60;more_available&#x60; or &#x60;next_learning_due_at&#x60;. An unknown &#x60;phase&#x60; value is treated as   &#x60;reviewing&#x60; when &#x60;items&#x60; is non-empty and as &#x60;empty&#x60; otherwise. - **C5 Materialization retries** (C-27): &#x60;reviewing&#x60; with empty &#x60;items&#x60; and   &#x60;to_materialize &gt; 0&#x60; (the three rounds ran out, or the caller is read-only): a caller   with &#x60;review&#x60; or &#x60;write&#x60; fetches again (at most 3 times in a row); a read-only caller   shows that new cards exist but cannot be created by it. - A client that sends &#x60;kinds&#x60; may get &#x60;reviewing&#x60; with empty &#x60;items&#x60; (the ready cards   are of a kind it excluded): for that filter there is nothing to show; &#x60;loop&#x60; still   describes the whole queue.  **Keep going (C-26, ADR-031).** With &#x60;keep_going&#x3D;true&#x60; the builder runs with &#x60;QueueLimits.keepGoing &#x3D; true&#x60; and &#x60;keepGoingNewBatch &#x3D; limit&#x60; (core &#x60;QueueBuilder&#x60; rule 6): the daily limits (&#x60;scheduler.new_per_day&#x60;, &#x60;scheduler.reviews_per_day&#x60;, deck &#x60;new_cards_per_day&#x60; / &#x60;reviews_per_day&#x60;) are lifted, at most about &#x60;limit&#x60; new cards are drawn per request (an add_kanji group may overshoot), and the order is ready learning, then **all** due reviews (most overdue first), then new cards - no mix: with a review cap the backlog is cleared first, then new cards follow without end; with &#x60;scheduler.reviews_unlimited&#x60; new cards follow without end and a review that becomes due is slotted in next. Everything else (eligibility, sibling burying, deck priority and order, disabled decks, learning) is unchanged. Cards not due before &#x60;day_ends_at&#x60; are never returned (no study-ahead). It is a stateless per-request flag: nothing is stored, no op is emitted, and the client keeps sending it on every fetch while the user keeps going. Cards answered this way are ordinary reviews and count against today&#39;s limits as usual. Everything in &#x60;loop&#x60; except &#x60;phase&#x60; and &#x60;keep_going&#x60; is the same with or without keep going (ADR-033 principle), so every client shows the same numbers at the same moment whatever mode it is in; so are the deprecated &#x60;counts&#x60;.  **No review limit (ADR-034).** If the setting &#x60;scheduler.reviews_unlimited&#x60; is &#x60;true&#x60; (SCHEMA.md 6.3; default &#x60;false&#x60;), every build (keep going or not) ignores &#x60;scheduler.reviews_per_day&#x60; and deck &#x60;reviews_per_day&#x60;: every review due before &#x60;day_ends_at&#x60; is returned and counted in &#x60;loop.ready.review&#x60; (&#x60;loop.reviews.limit&#x60; is null, &#x60;loop.backlog.beyond_limit&#x60; 0). The new goal and the proportional mix apply as usual; at the goal the phase is &#x60;goal_reached&#x60;. It is an ordinary setting written with &#x60;updateSettings&#x60;.  **Reading cards retired (ADR-035).** While the setting &#x60;cards.reading_enabled&#x60; is not &#x60;true&#x60; (default &#x60;false&#x60;; SCHEMA.md 6.4), vocab &#x60;reading&#x60; cards are never returned, never counted in &#x60;counts&#x60;, never bury their siblings, and today&#39;s reviews of them use no limit (core &#x60;QueueInput.readingEnabled&#x60;, QueueBuilder rule 0). Materialized deck entries get no &#x60;reading&#x60; card. Setting it &#x60;true&#x60; returns the same cards with their history.  **No learn-ahead (ADR-033).** A learning or relearning card is returned only once its &#x60;due_at&#x60; has passed (core &#x60;QueueLimits.learnAheadMs&#x60; &#x3D; 0 on the server and on devices). Cards due later today are counted in &#x60;loop.later_today&#x60; and are only a hint while anything else can be shown (reviews, new cards, Keep going). Only in phase &#x60;waiting&#x60; does the client show a waiting state (\&quot;next card in m:ss\&quot;) and fetch again at &#x60;loop.later_today.next_due_at&#x60; (rule C4). A card is never shown again before its learning step has passed.  **Keep-going scope (normative).** Rule C2 above: per client, persisted for the study day it was started in, sent on every fetch of that client, dropped when &#x60;study_day&#x60; changes.  **Old clients.** A client written before ADR-037 keeps working: &#x60;counts&#x60;, &#x60;more_available&#x60; and &#x60;next_learning_due_at&#x60; are still sent with their old meaning (see the schema). What it sees differently: the order (the proportional mix instead of the old interleave) and no per-session \&quot;done for now\&quot; (&#x60;scheduler.new_per_session&#x60; is ignored; &#x60;counts.new&#x60; is the day&#39;s remainder, never a session&#39;s).  **Deck entries become cards here (C-27, ADR-032).** Deck items &#x60;e:&lt;seq&gt;&#x60; with no note are new cards too (core &#x60;PendingEntry&#x60;; which entries qualify: contracts/core README \&quot;C-27\&quot;). The server passes them to the core builder, and when its build draws some (&#x60;toMaterialize&#x60; of the build that is **served** - &#x60;LoopResult.queue&#x60;; never the other build&#39;s - bounded by the same new-card budget as the cards: the day&#39;s remaining goal and the batch &#x60;limit&#x60;, or with keep going the batch alone; each entry counted as the whole add_kanji group its card will be, so an entry that would not fit is not drawn) and the caller holds &#x60;review&#x60; or &#x60;write&#x60;, the server creates them before answering, in one op transaction as the **server device**, exactly as contracts/core README \&quot;C-27\&quot; defines: per drawn entry, in the builder&#39;s order, the kanji notes the drawing deck&#39;s present &#x60;add_kanji&#x60; asks for (&#x60;note.created&#x60; &#x60;origin&#x60; &#x60;auto_kanji&#x60; + their &#x60;card.created&#x60;s; never a kanji the user deleted), then &#x60;note.created&#x60; {built-in vocab type, &#x60;entry_seq&#x60;, &#x60;fields&#x60; &#x60;{}&#x60;, &#x60;origin&#x60; &#x60;deck&#x60;} and one &#x60;card.created&#x60; per kind of the drawing deck&#39;s &#x60;card_kinds&#x60; (the vocab type&#39;s default kinds when that is absent, empty or invalid), minus kinds whose front would render empty. It then rebuilds and returns that queue; it materializes again (at most 3 rounds) only while the previous round&#39;s notes appear in &#x60;items&#x60;. This is the only place an API client&#39;s deck entries become notes: clients never mint these ops or call anything else for it; a device builds offline from the same core output and mints the same ops itself. - **Idempotent.** The transaction holds the sync lock and skips an entry a live note   already holds, so repeated or concurrent fetches never create a second note; a   fetch that draws nothing emits nothing (and takes no lock). - **Devices.** A device that created a note for the same entry offline converges by   SYNC.md 8.1 (the note that held the entry first survives; the other&#39;s cards and   reviews are merged into it). - **Read-only callers** (&#x60;read&#x60; only) get the queue without materializing: drawn   entries are counted in &#x60;to_materialize&#x60; (and the deprecated &#x60;counts.new&#x60;), not in   &#x60;items&#x60;; &#x60;loop.phase&#x60; is &#x60;reviewing&#x60; (rule C5). - &#x60;Kotobaya-Seq&#x60; is set when ops were emitted.  Each item carries everything needed to render and grade the card: the card with its note summary, the dictionary entry (when the note references one that exists), the example sentence chosen for rotating-example cards, the &#x60;review_type&#x60; an answer will be logged with, and a preview of the next due time per rating. Grammar cards also carry &#x60;grammar_sentence&#x60; (C-15): the rotated example sentence and span, chosen by the core picker with the rules on &#x60;GrammarCardSentence&#x60; (drill sessions use the same rules). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ReviewApi()
val limit : kotlin.Int = 20 // kotlin.Int | Maximum number of cards to return.
val deckId : kotlin.String = 01931f40-2000-7000-8000-000000000d01 // kotlin.String | Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (`404` otherwise).
val kinds : kotlin.collections.Set<kotlin.String> = ["learning","review"] // kotlin.collections.Set<kotlin.String> | Which queue sections to include, comma-separated. Default all three.
val keepGoing : kotlin.Boolean = true // kotlin.Boolean | C-26 (additive). `true` = keep going past today's limits (see the description; core `QueueBuilder` rule 6, with `keepGoingNewBatch = limit`). Default `false` = the normal queue. Scope (ADR-037, rule C2 in the description): a client that started keep going stores the `study_day` it was started in and sends `true` on every fetch while the latest response's `study_day` equals it; on a mismatch it drops the stored value and sends `false`. Per client, never synced. Only the literal values `true` and `false` are accepted; anything else is `422 validation_failed` with `errors[0].pointer = /keep_going` and `code = invalid_value`. Echoed as `loop.keep_going`. Older servers ignore the parameter (they return the normal queue and no `more_available`). 
try {
    val result : ReviewQueue = apiInstance.getReviewQueue(limit, deckId, kinds, keepGoing)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ReviewApi#getReviewQueue")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ReviewApi#getReviewQueue")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **limit** | **kotlin.Int**| Maximum number of cards to return. | [optional] [default to 20] |
| **deckId** | **kotlin.String**| Restrict new cards to this deck and reviews to cards whose note or entry is an item of it. Must be a live deck (&#x60;404&#x60; otherwise). | [optional] |
| **kinds** | [**kotlin.collections.Set&lt;kotlin.String&gt;**](kotlin.String.md)| Which queue sections to include, comma-separated. Default all three. | [optional] [default to setOf(&quot;new&quot;,&quot;learning&quot;,&quot;review&quot;)] [enum: new, learning, review] |
| **keepGoing** | **kotlin.Boolean**| C-26 (additive). &#x60;true&#x60; &#x3D; keep going past today&#39;s limits (see the description; core &#x60;QueueBuilder&#x60; rule 6, with &#x60;keepGoingNewBatch &#x3D; limit&#x60;). Default &#x60;false&#x60; &#x3D; the normal queue. Scope (ADR-037, rule C2 in the description): a client that started keep going stores the &#x60;study_day&#x60; it was started in and sends &#x60;true&#x60; on every fetch while the latest response&#39;s &#x60;study_day&#x60; equals it; on a mismatch it drops the stored value and sends &#x60;false&#x60;. Per client, never synced. Only the literal values &#x60;true&#x60; and &#x60;false&#x60; are accepted; anything else is &#x60;422 validation_failed&#x60; with &#x60;errors[0].pointer &#x3D; /keep_going&#x60; and &#x60;code &#x3D; invalid_value&#x60;. Echoed as &#x60;loop.keep_going&#x60;. Older servers ignore the parameter (they return the normal queue and no &#x60;more_available&#x60;).  | [optional] [default to false] |

### Return type

[**ReviewQueue**](ReviewQueue.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="undoReview"></a>
# **undoReview**
> ReviewResult undoReview(reviewUndoRequest)

Undo a review.

Server emits &#x60;review.undone&#x60; for the review named by &#x60;review_id&#x60; and recomputes the card.  - &#x60;review_id&#x60; is **required** (any review, whichever device logged it, &#x60;manual&#x60; rows   included). There is no implicit \&quot;undo my last review\&quot;: that would not be idempotent   (a retry after a lost response would undo the review before it). The web app keeps the   &#x60;id&#x60; it sent to &#x60;POST /review/answer&#x60; and passes it here. - Target already undone: &#x60;200&#x60; with the current state, no op (an undo cannot be undone).   Hence retries are safe. - Unknown &#x60;review_id&#x60;: &#x60;404 not_found&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = ReviewApi()
val reviewUndoRequest : ReviewUndoRequest = {"review_id":"01931f41-0000-7000-8000-000000000f01"} // ReviewUndoRequest | 
try {
    val result : ReviewResult = apiInstance.undoReview(reviewUndoRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ReviewApi#undoReview")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ReviewApi#undoReview")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **reviewUndoRequest** | [**ReviewUndoRequest**](ReviewUndoRequest.md)|  | |

### Return type

[**ReviewResult**](ReviewResult.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

