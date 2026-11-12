# CardCreatedSchema

Creates one card of a note. Schedule (cache) columns are never carried; a new card is state 'new'. OPS.md#cardcreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**buried_until** | **int** | Initial value of register &#39;buried_until&#39;. Default null. | [optional] 
**flags** | **int** | Initial value of register &#39;flags&#39;, 0-7. Default 0. | [optional] [default to 0]
**id** | **str** | Id of the new card. Equals the envelope&#39;s entity_id. | 
**kind** | **str** | Card kind, a key of the note type&#39;s templates (not validated against them). Immutable. | 
**note_id** | **str** | Parent note; must exist (may be tombstoned or merged). Immutable. | 
**preset_id** | **str** | Scheduling preset; must exist. Default: the Default preset 00000000-0000-7000-8000-000000000010. | [optional] 
**suspended** | **bool** | Initial value of register &#39;suspended&#39;. Default false. | [optional] [default to False]

## Example

```python
from kotobaya_client.models.card_created_schema import CardCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of CardCreatedSchema from a JSON string
card_created_schema_instance = CardCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(CardCreatedSchema.to_json())

# convert the object into a dict
card_created_schema_dict = card_created_schema_instance.to_dict()
# create an instance of CardCreatedSchema from a dict
card_created_schema_from_dict = CardCreatedSchema.from_dict(card_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


