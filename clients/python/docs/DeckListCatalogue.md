# DeckListCatalogue

Available generated lists.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[DeckListCatalogueEntry]**](DeckListCatalogueEntry.md) | Lists ordered by key. | 

## Example

```python
from kotobaya_client.models.deck_list_catalogue import DeckListCatalogue

# TODO update the JSON string below
json = "{}"
# create an instance of DeckListCatalogue from a JSON string
deck_list_catalogue_instance = DeckListCatalogue.from_json(json)
# print the JSON string representation of the object
print(DeckListCatalogue.to_json())

# convert the object into a dict
deck_list_catalogue_dict = deck_list_catalogue_instance.to_dict()
# create an instance of DeckListCatalogue from a dict
deck_list_catalogue_from_dict = DeckListCatalogue.from_dict(deck_list_catalogue_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


