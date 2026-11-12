# WordStatusSetSchema

Writes the explicit status override of a dictionary entry (one register: override_status + override_origin at override_hlc). OPS.md#wordstatus_set

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entry_seq** | **int** | Entry. entity_id is its decimal text. | 
**origin** | **str** | Who set it. Default &#39;manual&#39;. &#39;jpdb_import&#39; is only used by the server&#39;s import job. | [optional] [default to 'manual']
**status** | **str** | The override. | 

## Example

```python
from kotobaya_client.models.word_status_set_schema import WordStatusSetSchema

# TODO update the JSON string below
json = "{}"
# create an instance of WordStatusSetSchema from a JSON string
word_status_set_schema_instance = WordStatusSetSchema.from_json(json)
# print the JSON string representation of the object
print(WordStatusSetSchema.to_json())

# convert the object into a dict
word_status_set_schema_dict = word_status_set_schema_instance.to_dict()
# create an instance of WordStatusSetSchema from a dict
word_status_set_schema_from_dict = WordStatusSetSchema.from_dict(word_status_set_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


