
# QueueItem

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **card** | [**Card**](Card.md) | The card, with &#x60;note&#x60;. |  |
| **entry** | [**DictEntry**](DictEntry.md) | The note&#39;s dictionary entry, or null (no reference, or entry missing). |  |
| **preview** | [**SchedulePreview**](SchedulePreview.md) | Null for drill and cram items. |  |
| **reviewType** | [**inline**](#ReviewType) | Review type an answer will be logged with. |  |
| **sentence** | [**Sentence**](Sentence.md) | Example sentence chosen for this showing (grammar cloze and other rotating-example kinds); send its id back as &#x60;sentence_id&#x60;. Null otherwise. |  |
| **kanji** | [**Kanji**](Kanji.md) | For a note of base type &#x60;kanji&#x60;, the &#x60;ref.kanji&#x60; row of its &#x60;kanji_literal&#x60; (C-2); null for other notes or when the kanji is not in &#x60;ref.kanji&#x60;. The client passes &#x60;literal&#x60;, &#x60;meanings&#x60;, &#x60;on_readings&#x60; (onyomi), &#x60;kun_readings&#x60; (kunyomi) and &#x60;stroke_count&#x60; to the core renderer as &#x60;RenderInput.kanji&#x60; / &#x60;JsRenderInput.kanji&#x60;, so a kanji note with empty fields (&#x60;auto_kanji&#x60;, &#x60;jpdb_import&#x60;) renders from it identically on every host. Absent is treated as null. |  [optional] |


<a id="ReviewType"></a>
## Enum: review_type
| Name | Value |
| ---- | ----- |
| reviewType | scheduled, learning, drill, cram |



