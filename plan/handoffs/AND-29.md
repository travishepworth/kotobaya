# HANDOFF AND-29

STATUS: DONE
BRANCH: t/AND-29
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Route table: `resolveSpeech` (proxy) in `ApiOperations.kt`; `RouteTableTest` count 123→124. Autoplay (`mediaPlaybackRequiresUserGesture=false`) and the blob intercept (serves cache, downloads on miss) already existed: unchanged.
- Schema: `1.sqm` migration (v1→v2) creates `tts_audio`; `TtsAudioTable`, `DeviceWordAudioLookup` (row + cached-or-online rule) in `media/WordAudioSupport.kt`.
- Local queue: `QueueViews` fills `word_audio` via core `DefaultWordAudioResolver` (getReviewQueue + drill); parity goldens of SRV-29 pass.
- Prefetch: `WordAudioPrefetch.kt` (`WordAudioSet` = P(now), resolve pass in chunks of 200, early stop), `SpeechResolverHttp.kt`; `AudioPrefetchWorker` resolves then downloads; triggers: sync, app open, 30 s after a queue load (`AudioPrefetchTrigger`).

## Acceptance → tests
- migration, prefetch picks right pairs, lookup rule → `WordAudioPrefetchTest`; local word_audio parity → `QueueParityTest`/`LocalParityTest` (goldens).

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Part (b) of P uses a LocalQueueLoader with a read-only materializer; "30 s after materialized" is approximated by any queue load with `new` (unique KEEP work dedupes). TtsAudioNeeds fabricates due times (`now - size + i`) so the existing planner keeps P order and protects tts blobs from eviction.
- No tests for the HTTP resolver or the 200-chunk early-stop.
