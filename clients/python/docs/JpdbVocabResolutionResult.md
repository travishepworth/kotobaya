# JpdbVocabResolutionResult

Outcome of setting or clearing a correction.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**jobs** | [**List[Job]**](Job.md) | One &#x60;jpdb-deck-import&#x60; re-publish job (&#x60;remap_only&#x60;) per affected live media - newly enqueued or already waiting; empty when nothing changed or no live media contains the vid. | 
**vocab** | [**JpdbVocab**](JpdbVocab.md) |  | 

## Example

```python
from kotobaya_client.models.jpdb_vocab_resolution_result import JpdbVocabResolutionResult

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbVocabResolutionResult from a JSON string
jpdb_vocab_resolution_result_instance = JpdbVocabResolutionResult.from_json(json)
# print the JSON string representation of the object
print(JpdbVocabResolutionResult.to_json())

# convert the object into a dict
jpdb_vocab_resolution_result_dict = jpdb_vocab_resolution_result_instance.to_dict()
# create an instance of JpdbVocabResolutionResult from a dict
jpdb_vocab_resolution_result_from_dict = JpdbVocabResolutionResult.from_dict(jpdb_vocab_resolution_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


