# NoteMergedSchema

Server verdict that two live notes referenced the same entry, kanji or grammar point. Every replica runs the merge procedure. OPS.md#notemerged

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**loser_id** | **str** | The note that is tombstoned and gets merged_into_id &#x3D; survivor_id. Equals entity_id. | 
**survivor_id** | **str** | The note that already held the reference. Never itself merged at the time the op is emitted. Differs from loser_id. | 

## Example

```python
from kotobaya_client.models.note_merged_schema import NoteMergedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of NoteMergedSchema from a JSON string
note_merged_schema_instance = NoteMergedSchema.from_json(json)
# print the JSON string representation of the object
print(NoteMergedSchema.to_json())

# convert the object into a dict
note_merged_schema_dict = note_merged_schema_instance.to_dict()
# create an instance of NoteMergedSchema from a dict
note_merged_schema_from_dict = NoteMergedSchema.from_dict(note_merged_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


