# DeckUpdatedSchema

Sets a subset of a deck's registers, all at the op's hlc. OPS.md#deckupdated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the deck. Equals the envelope&#39;s entity_id. | 
**set** | [**DeckUpdatedSchemaSet**](DeckUpdatedSchemaSet.md) |  | 

## Example

```python
from kotobaya_client.models.deck_updated_schema import DeckUpdatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckUpdatedSchema from a JSON string
deck_updated_schema_instance = DeckUpdatedSchema.from_json(json)
# print the JSON string representation of the object
print(DeckUpdatedSchema.to_json())

# convert the object into a dict
deck_updated_schema_dict = deck_updated_schema_instance.to_dict()
# create an instance of DeckUpdatedSchema from a dict
deck_updated_schema_from_dict = DeckUpdatedSchema.from_dict(deck_updated_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


