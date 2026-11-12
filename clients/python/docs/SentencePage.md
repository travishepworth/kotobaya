# SentencePage

A page of sentences.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Sentence]**](Sentence.md) | Sentences in the documented order. | 
**next_cursor** | **str** | Cursor of the next page, or null. | 

## Example

```python
from kotobaya_client.models.sentence_page import SentencePage

# TODO update the JSON string below
json = "{}"
# create an instance of SentencePage from a JSON string
sentence_page_instance = SentencePage.from_json(json)
# print the JSON string representation of the object
print(SentencePage.to_json())

# convert the object into a dict
sentence_page_dict = sentence_page_instance.to_dict()
# create an instance of SentencePage from a dict
sentence_page_from_dict = SentencePage.from_dict(sentence_page_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


