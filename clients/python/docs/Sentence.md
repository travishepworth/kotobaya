# Sentence

A sentence of imported media (`app.sentence`) with its tokens and live grammar spans.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**audio_blob_hash** | **str** | Audio clip blob. | 
**end_ms** | **int** | Subtitle end. | 
**grammar_spans** | [**List[GrammarSpanRef]**](GrammarSpanRef.md) | Live spans (detected and manual) whose grammar point is live, by &#x60;start_offset&#x60;. | 
**id** | **str** | Sentence id (stable across re-parses while the text is unchanged). | 
**image_blob_hash** | **str** | Image blob (manga page crop, screenshot). | 
**location** | **Dict[str, object]** | Source position for non-timed media, e.g. &#x60;{\&quot;page\&quot;: 12, \&quot;box\&quot;: [x, y, w, h]}&#x60;. | 
**media_id** | **str** | Owning media. | 
**ordinal** | **int** | 0-based order within the segment. | 
**origin** | **str** | &#x60;generated&#x60; &#x3D; LLM-generated example; always label it as such. | 
**segment_id** | **str** | Owning segment. | 
**start_ms** | **int** | Subtitle start within the segment; null for text sources. | 
**text** | **str** | Original text. | 
**tokens** | [**List[SentenceToken]**](SentenceToken.md) | Tokens in ascending, non-overlapping order; gaps are untokenised text. | 
**unknown_count** | **int** | Number of unknown words now, as defined on the &#x60;filter&#x60; parameter (computed at request time). | 

## Example

```python
from kotobaya_client.models.sentence import Sentence

# TODO update the JSON string below
json = "{}"
# create an instance of Sentence from a JSON string
sentence_instance = Sentence.from_json(json)
# print the JSON string representation of the object
print(Sentence.to_json())

# convert the object into a dict
sentence_dict = sentence_instance.to_dict()
# create an instance of Sentence from a dict
sentence_from_dict = Sentence.from_dict(sentence_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


