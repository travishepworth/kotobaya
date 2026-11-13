# GrammarCardSentence

C-15. The example sentence for one showing of a grammar card (SPEC \"Grammar review\": the sentence rotates on each review; i+0 sentences first). It is exactly the `GrammarCandidate` returned by the core's `GrammarCardSentences.pick(candidates, lastShown, rotation)`, plus its inputs and source. Servers MUST call that core function; they never re-implement the choice.  **Candidates** (one per distinct (`sentence_id`, `start_offset`, `end_offset`)): every live span (`deleted_at` null; origin `detected` or `manual`) of the note's `grammar_point_id` in an `app.sentence` row with `origin = media` whose media is live. Generated sentences are never candidates (SPEC: kept separate); Tatoeba sentences have no spans and are not candidates. A span whose grammar point is deleted, whose sentence is missing, or that is not a valid UTF-16 range of the text (`GrammarCardSentences.isValidSpan`: empty, out of range, or cutting a surrogate pair) is dropped. `unknownCount` = the sentence's `Sentence.unknown_count` (the `filter` parameter's definition, now).  **Inputs**: - `rotation` = `card.reps` of the card as returned in the same item (after replay). - `lastShown` = the `sentence_id` of the card's latest review (highest `hlc`) among   `review_log` rows with `undone_at` null and `review_type` not `manual`, counting rows   logged on cards merged into it; null if there is none or its `sentence_id` is null   (earlier rows are not consulted).  **Determinism**: the result is a pure function of the replica's state. Fetching the queue again without answering returns the same sentence; answering moves both inputs (the answered sentence becomes `lastShown`; a schedule answer also increments `reps`), so the next showing differs whenever two or more candidate sentences exist; undoing that answer restores both inputs and therefore the earlier sentence. The pick can change between fetches only when the candidates change (new detection, a span removed, a word becoming known); clients must not swap the sentence of an item already shown. Drill and cram answers log `sentence_id` but leave `reps` unchanged; `lastShown` still advances, so drills rotate too.  **Offline / Android**: a device building its queue locally runs the same pick over its replica: candidates from installed media snapshots (sentences, detected spans) plus manual spans from `grammar.span_put`, `unknownCount` from local word statuses, and `card.reps` / `review_log` from local state. Media whose snapshot is not installed contribute nothing; with no candidate the device renders with `cloze = null`, as for a null `QueueItem.grammar_sentence`. A device and the server may pick differently when their replicas differ; this only affects display, and the review logs whatever was shown. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**end_offset** | **int** | UTF-16 end of the span, exclusive; &#x60;start_offset &lt; end_offset &lt;&#x3D; text length&#x60;, never inside a surrogate pair. | 
**last_shown_sentence_id** | **str** | The &#x60;lastShown&#x60; input of the pick. Informational. | 
**rotation** | **int** | The &#x60;rotation&#x60; input of the pick (&#x60;card.reps&#x60;). Informational, for debugging and for checking that a device agrees. | 
**sentence_id** | **str** | The chosen sentence (&#x60;app.sentence.id&#x60;); equals &#x60;QueueItem.sentence.id&#x60;. Send it as &#x60;ReviewAnswerRequest.sentence_id&#x60;. | 
**source** | [**ExampleSentenceSource**](ExampleSentenceSource.md) |  | 
**start_offset** | **int** | UTF-16 start of the chosen span of the card&#39;s grammar point, inclusive. Cloze blanks it, recognition highlights it, spot reveals it on the back. Other spans of the same point in this sentence are in &#x60;QueueItem.sentence.grammar_spans&#x60; and are not highlighted by the core renderer. | 
**text** | **str** | The sentence text, identical to &#x60;QueueItem.sentence.text&#x60;. | 
**translation** | **str** | English translation for the back, or null. Always null for now (mined media sentences carry none); reserved so the renderer&#39;s &#x60;{{cloze:translation}}&#x60; can be filled later without a contract change. | 
**unknown_count** | **int** | The sentence&#39;s unknown-word count the pick used (0 &#x3D; i+0). | 

## Example

```python
from kotobaya_client.models.grammar_card_sentence import GrammarCardSentence

# TODO update the JSON string below
json = "{}"
# create an instance of GrammarCardSentence from a JSON string
grammar_card_sentence_instance = GrammarCardSentence.from_json(json)
# print the JSON string representation of the object
print(GrammarCardSentence.to_json())

# convert the object into a dict
grammar_card_sentence_dict = grammar_card_sentence_instance.to_dict()
# create an instance of GrammarCardSentence from a dict
grammar_card_sentence_from_dict = GrammarCardSentence.from_dict(grammar_card_sentence_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


