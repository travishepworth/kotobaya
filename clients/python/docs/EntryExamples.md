# EntryExamples

Example sentences for an entry.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**media** | [**List[Sentence]**](Sentence.md) | Sentences from your media. | 
**tatoeba** | [**List[TatoebaSentence]**](TatoebaSentence.md) | Tatoeba pairs. | 

## Example

```python
from kotobaya_client.models.entry_examples import EntryExamples

# TODO update the JSON string below
json = "{}"
# create an instance of EntryExamples from a JSON string
entry_examples_instance = EntryExamples.from_json(json)
# print the JSON string representation of the object
print(EntryExamples.to_json())

# convert the object into a dict
entry_examples_dict = entry_examples_instance.to_dict()
# create an instance of EntryExamples from a dict
entry_examples_from_dict = EntryExamples.from_dict(entry_examples_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


