# CardPresetSetSchema

Writes register 'preset_id'. A winning write forces a replay of the card. OPS.md#cardpreset_set

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the card. Equals the envelope&#39;s entity_id. | 
**preset_id** | **str** | fsrs_params.id; must exist (may be tombstoned: the card then schedules with Default). | 

## Example

```python
from kotobaya_client.models.card_preset_set_schema import CardPresetSetSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardPresetSetSchema from a JSON string
card_preset_set_schema_instance = CardPresetSetSchema.from_json(json)
# print the JSON string representation of the object
print(CardPresetSetSchema.to_json())

# convert the object into a dict
card_preset_set_schema_dict = card_preset_set_schema_instance.to_dict()
# create an instance of CardPresetSetSchema from a dict
card_preset_set_schema_from_dict = CardPresetSetSchema.from_dict(card_preset_set_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


