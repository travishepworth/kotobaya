# CardSuspendedSchema

Writes register 'suspended' = true. OPS.md#cardsuspended

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.card_suspended_schema import CardSuspendedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardSuspendedSchema from a JSON string
card_suspended_schema_instance = CardSuspendedSchema.from_json(json)
# print the JSON string representation of the object
print(CardSuspendedSchema.to_json())

# convert the object into a dict
card_suspended_schema_dict = card_suspended_schema_instance.to_dict()
# create an instance of CardSuspendedSchema from a dict
card_suspended_schema_from_dict = CardSuspendedSchema.from_dict(card_suspended_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


