
# CardText

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **back** | **kotlin.String** | The answer (what the back adds to the front). Clients show it only once revealed; it is always sent. May be empty. It contains the reading where the kind reveals it, so a layout that prints &#x60;reading&#x60; separately should not also print the whole &#x60;back&#x60;. |  |
| **front** | **kotlin.String** | The question. Empty iff it has no text (audio-only &#x60;listening&#x60; fronts, or an empty note); clients then show their own placeholder (e.g. \&quot;listening card\&quot;). |  |
| **reading** | **kotlin.String** | The kana reading (&#x60;reading[kana]&#x60; of vocab, phrase and sentence notes) or the readings of a kanji note (&#x60;readings&#x60;); null when empty and for grammar and custom notes. Answer-side information, already contained in &#x60;back&#x60; where the kind shows it; for layouts that place it separately. |  |
| **sentence** | **kotlin.String** | The context sentence the built-in front shows (the note&#39;s &#x60;sentence&#x60; field, &#x60;[kanji]&#x60; mode), for every base type except sentence; null when empty. Not part of &#x60;front&#x60; or &#x60;back&#x60; under built-in templates (a custom template may include it). Not the grammar cloze sentence. |  |



