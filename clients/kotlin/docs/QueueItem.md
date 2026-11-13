
# QueueItem

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **card** | [**Card**](Card.md) | The card, with &#x60;note&#x60;. |  |
| **entry** | [**DictEntry**](DictEntry.md) | The note&#39;s dictionary entry, or null (no reference, or entry missing). |  |
| **preview** | [**SchedulePreview**](SchedulePreview.md) | Null for drill and cram items. |  |
| **reviewType** | [**inline**](#ReviewType) | Review type an answer will be logged with. |  |
| **sentence** | [**Sentence**](Sentence.md) | Example sentence chosen for this showing (grammar cloze and other rotating-example kinds); send its id back as &#x60;sentence_id&#x60;. Null otherwise. For a grammar card (C-15) it is the full row of &#x60;grammar_sentence.sentence_id&#x60; (tokens for furigana, audio, image) when &#x60;grammar_sentence&#x60; is non-null, and null when &#x60;grammar_sentence&#x60; is null. |  |
| **grammarSentence** | [**GrammarCardSentence**](GrammarCardSentence.md) | C-15 (additive). The example sentence and span to show for a card of a grammar note (&#x60;card.note.grammar_point_id&#x60; non-null) of any kind except &#x60;production&#x60;, chosen at queue-build time by the shared core picker &#x60;GrammarCardSentences.pick&#x60; (rotation, i+0 first). Null for all other cards, and for grammar cards with no usable candidate. Absent (older server) is treated as null. The rules are on &#x60;GrammarCardSentence&#x60;.  Clients pass it to the core renderer unchanged as &#x60;RenderInput.cloze&#x60; / &#x60;JsRenderInput.cloze&#x60; (&#x60;sentence_id&#x60; → &#x60;sentenceId&#x60;, &#x60;text&#x60;, &#x60;start_offset&#x60; → &#x60;start&#x60;, &#x60;end_offset&#x60; → &#x60;end&#x60;, &#x60;translation&#x60;), keep showing it for the whole showing of this item (front, back, undo of a later card, redisplay), and send &#x60;sentence_id&#x60; back as &#x60;ReviewAnswerRequest.sentence_id&#x60;. When it is null for a grammar card, they render with &#x60;cloze &#x3D; null&#x60; (the renderer emits the &#x60;missing_cloze&#x60; warning) and SHOULD show an i18n \&quot;no example sentence yet\&quot; notice with the point&#39;s pattern.  |  [optional] |
| **kanji** | [**Kanji**](Kanji.md) | For a note of base type &#x60;kanji&#x60;, the &#x60;ref.kanji&#x60; row of its &#x60;kanji_literal&#x60; (C-2); null for other notes or when the kanji is not in &#x60;ref.kanji&#x60;. The client passes &#x60;literal&#x60;, &#x60;meanings&#x60;, &#x60;on_readings&#x60; (onyomi), &#x60;kun_readings&#x60; (kunyomi) and &#x60;stroke_count&#x60; to the core renderer as &#x60;RenderInput.kanji&#x60; / &#x60;JsRenderInput.kanji&#x60;, so a kanji note with empty fields (&#x60;auto_kanji&#x60;, &#x60;jpdb_import&#x60;) renders from it identically on every host. Absent is treated as null. |  [optional] |


<a id="ReviewType"></a>
## Enum: review_type
| Name | Value |
| ---- | ----- |
| reviewType | scheduled, learning, drill, cram |



