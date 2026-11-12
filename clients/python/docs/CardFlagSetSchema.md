# CardFlagSetSchema

Writes register 'flags'. OPS.md#cardflag_set

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**flags** | **int** | 0 &#x3D; none, 1-7 &#x3D; flag colour. | 
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.card_flag_set_schema import CardFlagSetSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardFlagSetSchema from a JSON string
card_flag_set_schema_instance = CardFlagSetSchema.from_json(json)
# print the JSON string representation of the object
print(CardFlagSetSchema.to_json())

# convert the object into a dict
card_flag_set_schema_dict = card_flag_set_schema_instance.to_dict()
# create an instance of CardFlagSetSchema from a dict
card_flag_set_schema_from_dict = CardFlagSetSchema.from_dict(card_flag_set_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


