# CardUnburiedSchema

Writes register 'buried_until' = null. OPS.md#cardunburied

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.card_unburied_schema import CardUnburiedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardUnburiedSchema from a JSON string
card_unburied_schema_instance = CardUnburiedSchema.from_json(json)
# print the JSON string representation of the object
print(CardUnburiedSchema.to_json())

# convert the object into a dict
card_unburied_schema_dict = card_unburied_schema_instance.to_dict()
# create an instance of CardUnburiedSchema from a dict
card_unburied_schema_from_dict = CardUnburiedSchema.from_dict(card_unburied_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


