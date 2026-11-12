# DeckDeletedSchema

Tombstones a deck (permanent). Clients may not delete a media deck directly (delete the media). OPS.md#deckdeleted

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **str** | Id of the deck. Equals the envelope&#39;s entity_id. | 

## Example

```python
from kotobaya_client.models.deck_deleted_schema import DeckDeletedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckDeletedSchema from a JSON string
deck_deleted_schema_instance = DeckDeletedSchema.from_json(json)
# print the JSON string representation of the object
print(DeckDeletedSchema.to_json())

# convert the object into a dict
deck_deleted_schema_dict = deck_deleted_schema_instance.to_dict()
# create an instance of DeckDeletedSchema from a dict
deck_deleted_schema_from_dict = DeckDeletedSchema.from_dict(deck_deleted_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


