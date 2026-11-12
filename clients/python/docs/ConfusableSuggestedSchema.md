# ConfusableSuggestedSchema

Server suggestion (status 'suggested'). Never overrides a user decision. OPS.md#confusablesuggested

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**note_a_id** | **str** | The smaller note id of the pair (lowercase text order). Must exist. | 
**note_b_id** | **str** | The larger note id. note_a_id &lt; note_b_id. Must exist. | 
**reason** | **str** | Why they are confused. entity_id &#x3D; &#39;&lt;note_a_id&gt;|&lt;note_b_id&gt;|&lt;reason&gt;&#39;. | 

## Example

```python
from kotobaya_client.models.confusable_suggested_schema import ConfusableSuggestedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ConfusableSuggestedSchema from a JSON string
confusable_suggested_schema_instance = ConfusableSuggestedSchema.from_json(json)
# print the JSON string representation of the object
print(ConfusableSuggestedSchema.to_json())

# convert the object into a dict
confusable_suggested_schema_dict = confusable_suggested_schema_instance.to_dict()
# create an instance of ConfusableSuggestedSchema from a dict
confusable_suggested_schema_from_dict = ConfusableSuggestedSchema.from_dict(confusable_suggested_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


