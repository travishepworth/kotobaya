# DeckCreatedSchema

Creates a deck. Clients may create type user and smart only; media and list decks are created by the server. OPS.md#deckcreated

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enabled** | **bool** | false &#x3D; supplies no new cards. Default true. | [optional] [default to True]
**id** | **str** | Id of the new deck (for a media deck: equal to media_id). Equals the envelope&#39;s entity_id. | 
**language** | **str** | Default &#39;ja&#39;. | [optional] 
**list_key** | **str** | Required iff type &#x3D; list. Slug, e.g. jlpt-n3. | [optional] 
**media_id** | **str** | Required iff type &#x3D; media; equals id. | [optional] 
**name** | **str** | Display name; not unique. | 
**new_cards_per_day** | **int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. | [optional] 
**priority** | **int** | New cards are drawn from enabled decks in ascending (priority, id). Default 0. | [optional] [default to 0]
**query** | **str** | Card search-language string. Required iff type &#x3D; smart. | [optional] 
**reviews_per_day** | **int** | Per-deck daily new-card limit; null &#x3D; only the global limit applies. | [optional] 
**settings** | **object** | Open object; each top-level key is its own register &#39;settings.&lt;key&gt;&#39;. Known keys: order, add_kanji, card_kinds. Keys whose value is null, &#39;&#39;, [] or {} are dropped at creation. | [optional] 
**type** | **str** | Immutable. media and list: server-emitted only. | 

## Example

```python
from kotobaya_client.models.deck_created_schema import DeckCreatedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of DeckCreatedSchema from a JSON string
deck_created_schema_instance = DeckCreatedSchema.from_json(json)
# print the JSON string representation of the object
print(DeckCreatedSchema.to_json())

# convert the object into a dict
deck_created_schema_dict = deck_created_schema_instance.to_dict()
# create an instance of DeckCreatedSchema from a dict
deck_created_schema_from_dict = DeckCreatedSchema.from_dict(deck_created_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


