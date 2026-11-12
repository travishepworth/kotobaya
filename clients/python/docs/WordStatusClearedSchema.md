# WordStatusClearedSchema

Clears the override (same register as word.status_set, value null), so the effective status falls back to the automatic one. OPS.md#wordstatus_cleared

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | Entry. entity_id is its decimal text. | 

## Example

```python
from kotobaya_client.models.word_status_cleared_schema import WordStatusClearedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of WordStatusClearedSchema from a JSON string
word_status_cleared_schema_instance = WordStatusClearedSchema.from_json(json)
# print the JSON string representation of the object
print(WordStatusClearedSchema.to_json())

# convert the object into a dict
word_status_cleared_schema_dict = word_status_cleared_schema_instance.to_dict()
# create an instance of WordStatusClearedSchema from a dict
word_status_cleared_schema_from_dict = WordStatusClearedSchema.from_dict(word_status_cleared_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


