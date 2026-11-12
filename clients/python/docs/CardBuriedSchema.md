# CardBuriedSchema

Writes register 'buried_until' (manual bury). OPS.md#cardburied

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 
**until** | **int** | The card is hidden while now &lt; until. Clients send the start of the next study day. | 

## Example

```python
from kotobaya_client.models.card_buried_schema import CardBuriedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardBuriedSchema from a JSON string
card_buried_schema_instance = CardBuriedSchema.from_json(json)
# print the JSON string representation of the object
print(CardBuriedSchema.to_json())

# convert the object into a dict
card_buried_schema_dict = card_buried_schema_instance.to_dict()
# create an instance of CardBuriedSchema from a dict
card_buried_schema_from_dict = CardBuriedSchema.from_dict(card_buried_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


