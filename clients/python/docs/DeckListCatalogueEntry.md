# DeckListCatalogueEntry

A generated list that can be enabled.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_id** | **str** | The live deck for this key, or null if not enabled. | 
**item_count** | **int** | Entries in the list for the current dictionary release. | 
**list_key** | **str** | Stable slug. | 
**title** | **str** | Display title; default deck name. | 

## Example

```python
from kotobaya_client.models.deck_list_catalogue_entry import DeckListCatalogueEntry

# TODO update the JSON string below
json = "{}"
# create an instance of DeckListCatalogueEntry from a JSON string
deck_list_catalogue_entry_instance = DeckListCatalogueEntry.from_json(json)
# print the JSON string representation of the object
print(DeckListCatalogueEntry.to_json())

# convert the object into a dict
deck_list_catalogue_entry_dict = deck_list_catalogue_entry_instance.to_dict()
# create an instance of DeckListCatalogueEntry from a dict
deck_list_catalogue_entry_from_dict = DeckListCatalogueEntry.from_dict(deck_list_catalogue_entry_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


