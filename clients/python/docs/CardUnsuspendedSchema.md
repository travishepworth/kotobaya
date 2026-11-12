# CardUnsuspendedSchema

Writes register 'suspended' = false. OPS.md#cardunsuspended

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.card_unsuspended_schema import CardUnsuspendedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardUnsuspendedSchema from a JSON string
card_unsuspended_schema_instance = CardUnsuspendedSchema.from_json(json)
# print the JSON string representation of the object
print(CardUnsuspendedSchema.to_json())

# convert the object into a dict
card_unsuspended_schema_dict = card_unsuspended_schema_instance.to_dict()
# create an instance of CardUnsuspendedSchema from a dict
card_unsuspended_schema_from_dict = CardUnsuspendedSchema.from_dict(card_unsuspended_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


