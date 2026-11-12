# DeckPatch

Changes to a deck. At least one property.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enabled** | **bool** | Whether the deck supplies new cards. | [optional] 
**name** | **str** | Display name. | [optional] 
**new_cards_per_day** | **int** | Per-deck new-card limit; null removes it. | [optional] 
**priority** | **int** | New-card priority. | [optional] 
**query** | **str** | Smart decks only - new search string. | [optional] 
**reviews_per_day** | **int** | Per-deck review limit; null removes it. | [optional] 
**settings** | **Dict[str, object]** | Settings keys to write (partial map); a null value removes the key. Known keys as in &#x60;DeckSettings&#x60;. | [optional] 

## Example

```python
from kotobaya_client.models.deck_patch import DeckPatch

# TODO update the JSON string below
json = "{}"
# create an instance of DeckPatch from a JSON string
deck_patch_instance = DeckPatch.from_json(json)
# print the JSON string representation of the object
print(DeckPatch.to_json())

# convert the object into a dict
deck_patch_dict = deck_patch_instance.to_dict()
# create an instance of DeckPatch from a dict
deck_patch_from_dict = DeckPatch.from_dict(deck_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


