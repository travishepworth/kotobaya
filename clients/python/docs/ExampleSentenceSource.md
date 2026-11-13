# ExampleSentenceSource

C-15. Where an example sentence comes from, for the \"source\" line on the back and a link to the reader.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**location** | **Dict[str, object]** | &#x60;Sentence.location&#x60; (null for timed media). | 
**media_id** | **str** | The sentence&#39;s media. | 
**media_title** | **str** | &#x60;Media.title&#x60;. | 
**segment_id** | **str** | The sentence&#39;s segment. | 
**segment_title** | **str** | &#x60;MediaSegment.title&#x60;; may be empty. | 
**start_ms** | **int** | &#x60;Sentence.start_ms&#x60; (null for text sources). | 

## Example

```python
from kotobaya_client.models.example_sentence_source import ExampleSentenceSource

# TODO update the JSON string below
json = "{}"
# create an instance of ExampleSentenceSource from a JSON string
example_sentence_source_instance = ExampleSentenceSource.from_json(json)
# print the JSON string representation of the object
print(ExampleSentenceSource.to_json())

# convert the object into a dict
example_sentence_source_dict = example_sentence_source_instance.to_dict()
# create an instance of ExampleSentenceSource from a dict
example_sentence_source_from_dict = ExampleSentenceSource.from_dict(example_sentence_source_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


