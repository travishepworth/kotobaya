# ConfusableConfirmedSchema

User confirms the pair (status 'confirmed'); creates the row if nobody suggested it. OPS.md#confusableconfirmed

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**note_a_id** | **str** | The smaller note id of the pair (lowercase text order). Must exist. | 
**note_b_id** | **str** | The larger note id. note_a_id &lt; note_b_id. Must exist. | 
**reason** | **str** | Why they are confused. entity_id &#x3D; &#39;&lt;note_a_id&gt;|&lt;note_b_id&gt;|&lt;reason&gt;&#39;. | 

## Example

```python
from kotobaya_client.models.confusable_confirmed_schema import ConfusableConfirmedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ConfusableConfirmedSchema from a JSON string
confusable_confirmed_schema_instance = ConfusableConfirmedSchema.from_json(json)
# print the JSON string representation of the object
print(ConfusableConfirmedSchema.to_json())

# convert the object into a dict
confusable_confirmed_schema_dict = confusable_confirmed_schema_instance.to_dict()
# create an instance of ConfusableConfirmedSchema from a dict
confusable_confirmed_schema_from_dict = ConfusableConfirmedSchema.from_dict(confusable_confirmed_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


