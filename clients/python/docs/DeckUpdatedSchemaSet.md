# DeckUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enabled** | **bool** | Register &#39;enabled&#39;. | [optional] 
**name** | **str** | Register &#39;name&#39;. | [optional] 
**new_cards_per_day** | **int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. | [optional] 
**priority** | **int** | Register &#39;priority&#39;. | [optional] 
**query** | **str** | Register &#39;query&#39;. Only valid for smart decks (server rejects otherwise). | [optional] 
**reviews_per_day** | **int** | Per-deck daily review limit; null &#x3D; only the global limit applies. | [optional] 
**settings** | **object** | Open object; each top-level key is its own register &#39;settings.&lt;key&gt;&#39;. Known keys: order, add_kanji, card_kinds. A null value removes the key (the register keeps its clock). | [optional] 

## Example

```python
from kotobaya_client.models.deck_updated_schema_set import DeckUpdatedSchemaSet

# TODO update the JSON string below
json = "{}"
# create an instance of DeckUpdatedSchemaSet from a JSON string
deck_updated_schema_set_instance = DeckUpdatedSchemaSet.from_json(json)
# print the JSON string representation of the object
print(DeckUpdatedSchemaSet.to_json())

# convert the object into a dict
deck_updated_schema_set_dict = deck_updated_schema_set_instance.to_dict()
# create an instance of DeckUpdatedSchemaSet from a dict
deck_updated_schema_set_from_dict = DeckUpdatedSchemaSet.from_dict(deck_updated_schema_set_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


