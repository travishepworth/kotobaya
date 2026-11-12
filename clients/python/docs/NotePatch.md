# NotePatch

Changes to a note. At least one property. See the register table on `PATCH /notes/{note_id}`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**add_tags** | **List[str]** | Tags to add to the current set. If the merged set would exceed 200 tags the request is &#x60;422 validation_failed&#x60;, error code &#x60;out_of_range&#x60; at &#x60;/add_tags&#x60; (C-2). | [optional] 
**entry_seq** | **int** | Re-match to another entry, or null to un-match (vocab notes only). | [optional] 
**fields** | **Dict[str, str]** | Field values to set (partial map); &#x60;\&quot;\&quot;&#x60; clears a field. | [optional] 
**mnemonic** | **str** | Mnemonic (Markdown). | [optional] 
**remove_tags** | **List[str]** | Tags to remove from the current set; absent ones are ignored. | [optional] 
**source_media_id** | **str** | Soft reference to the media. | [optional] 
**source_sentence** | **str** | Source sentence. | [optional] 
**source_sentence_id** | **str** | Soft reference to the sentence. | [optional] 
**tags** | **List[str]** | The whole new tag set. | [optional] 

## Example

```python
from kotobaya_client.models.note_patch import NotePatch

# TODO update the JSON string below
json = "{}"
# create an instance of NotePatch from a JSON string
note_patch_instance = NotePatch.from_json(json)
# print the JSON string representation of the object
print(NotePatch.to_json())

# convert the object into a dict
note_patch_dict = note_patch_instance.to_dict()
# create an instance of NotePatch from a dict
note_patch_from_dict = NotePatch.from_dict(note_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


