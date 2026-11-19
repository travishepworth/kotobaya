# SpeechResolveItem

One thing to resolve - exactly one of `text` (a pair, with optional `voice`) or `note_id`. The \"exactly one\" rule is checked by the server (`422`), not by this schema, so that generated clients get one plain type.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**note_id** | **str** | A note - its word audio is resolved as for a queue item. | [optional] 
**text** | **str** | Japanese text (plain, no HTML), spoken exactly as given. For word audio - &#x60;WordAudio.text&#x60;. | [optional] 
**voice** | **str** | VOICEVOX style id. Absent or null - the effective voice. Not allowed with &#x60;note_id&#x60;. | [optional] 

## Example

```python
from kotobaya_client.models.speech_resolve_item import SpeechResolveItem

# TODO update the JSON string below
json = "{}"
# create an instance of SpeechResolveItem from a JSON string
speech_resolve_item_instance = SpeechResolveItem.from_json(json)
# print the JSON string representation of the object
print(SpeechResolveItem.to_json())

# convert the object into a dict
speech_resolve_item_dict = speech_resolve_item_instance.to_dict()
# create an instance of SpeechResolveItem from a dict
speech_resolve_item_from_dict = SpeechResolveItem.from_dict(speech_resolve_item_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


