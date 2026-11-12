# MediaDeckOptions

Initial options of the media deck (only used when the import creates it).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**enabled** | **bool** | Whether the deck supplies new cards. | [optional] [default to True]
**new_cards_per_day** | **int** | Per-deck new-card limit. | [optional] 
**priority** | **int** | New-card priority. | [optional] [default to 0]

## Example

```python
from kotobaya_client.models.media_deck_options import MediaDeckOptions

# TODO update the JSON string below
json = "{}"
# create an instance of MediaDeckOptions from a JSON string
media_deck_options_instance = MediaDeckOptions.from_json(json)
# print the JSON string representation of the object
print(MediaDeckOptions.to_json())

# convert the object into a dict
media_deck_options_dict = media_deck_options_instance.to_dict()
# create an instance of MediaDeckOptions from a dict
media_deck_options_from_dict = MediaDeckOptions.from_dict(media_deck_options_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


