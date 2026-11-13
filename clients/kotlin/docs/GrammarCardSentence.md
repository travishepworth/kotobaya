
# GrammarCardSentence

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **endOffset** | **kotlin.Int** | UTF-16 end of the span, exclusive; &#x60;start_offset &lt; end_offset &lt;&#x3D; text length&#x60;, never inside a surrogate pair. |  |
| **lastShownSentenceId** | **kotlin.String** | The &#x60;lastShown&#x60; input of the pick. Informational. |  |
| **rotation** | **kotlin.Int** | The &#x60;rotation&#x60; input of the pick (&#x60;card.reps&#x60;). Informational, for debugging and for checking that a device agrees. |  |
| **sentenceId** | **kotlin.String** | The chosen sentence (&#x60;app.sentence.id&#x60;); equals &#x60;QueueItem.sentence.id&#x60;. Send it as &#x60;ReviewAnswerRequest.sentence_id&#x60;. |  |
| **source** | [**ExampleSentenceSource**](ExampleSentenceSource.md) |  |  |
| **startOffset** | **kotlin.Int** | UTF-16 start of the chosen span of the card&#39;s grammar point, inclusive. Cloze blanks it, recognition highlights it, spot reveals it on the back. Other spans of the same point in this sentence are in &#x60;QueueItem.sentence.grammar_spans&#x60; and are not highlighted by the core renderer. |  |
| **text** | **kotlin.String** | The sentence text, identical to &#x60;QueueItem.sentence.text&#x60;. |  |
| **translation** | **kotlin.String** | English translation for the back, or null. Always null for now (mined media sentences carry none); reserved so the renderer&#39;s &#x60;{{cloze:translation}}&#x60; can be filled later without a contract change. |  |
| **unknownCount** | **kotlin.Int** | The sentence&#39;s unknown-word count the pick used (0 &#x3D; i+0). |  |



