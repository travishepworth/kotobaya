# DeckSettings

Open settings object of a deck; each key is its own last-writer-wins register. Unknown keys are preserved.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**add_kanji** | **str** | Whether adding a vocab card also adds cards for its unlearned kanji. | [optional] 
**card_kinds** | **List[str]** | Card kinds to create for notes added from this deck, overriding note-type defaults. | [optional] 
**order** | **str** | New-card order inside the deck. Default &#x60;deck_frequency&#x60; for media decks, &#x60;position&#x60; otherwise. | [optional] 

## Example

```python
from kotobaya_client.models.deck_settings import DeckSettings

# TODO update the JSON string below
json = "{}"
# create an instance of DeckSettings from a JSON string
deck_settings_instance = DeckSettings.from_json(json)
# print the JSON string representation of the object
print(DeckSettings.to_json())

# convert the object into a dict
deck_settings_dict = deck_settings_instance.to_dict()
# create an instance of DeckSettings from a dict
deck_settings_from_dict = DeckSettings.from_dict(deck_settings_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


