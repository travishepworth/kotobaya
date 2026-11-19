# SpeechResolution

The answer for one `SpeechResolveItem` (`resolveSpeech` has the status table). Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | The audio blob when &#x60;status&#x60; is &#x60;ready&#x60;, else null. | 
**duration_ms** | **int** | Length of the audio when &#x60;status&#x60; is &#x60;ready&#x60; and &#x60;source&#x60; is &#x60;tts&#x60;, else null. | 
**retry_after_ms** | **int** | When to ask again - &#x60;pending&#x60; 1000 (60000 when held back by the backlog rule); &#x60;failed&#x60; the time left of the 600 s back-off; null for &#x60;ready&#x60; and &#x60;none&#x60;. | 
**source** | **str** | &#x60;tts&#x60; for a &#x60;text&#x60; item and for a note without its own audio; &#x60;note&#x60; for a note whose &#x60;audio&#x60; field holds a sound; null when &#x60;status&#x60; is &#x60;none&#x60;. | 
**status** | **str** | See &#x60;resolveSpeech&#x60;. Clients treat an unknown value as &#x60;failed&#x60;. | 
**text** | **str** | The text of the pair (the item&#39;s &#x60;text&#x60;, or the note&#39;s speech text); null when &#x60;source&#x60; is not &#x60;tts&#x60;. | 
**voice** | **str** | The voice of the pair after defaulting; null when &#x60;source&#x60; is not &#x60;tts&#x60;. | 

## Example

```python
from kotobaya_client.models.speech_resolution import SpeechResolution

# TODO update the JSON string below
json = "{}"
# create an instance of SpeechResolution from a JSON string
speech_resolution_instance = SpeechResolution.from_json(json)
# print the JSON string representation of the object
print(SpeechResolution.to_json())

# convert the object into a dict
speech_resolution_dict = speech_resolution_instance.to_dict()
# create an instance of SpeechResolution from a dict
speech_resolution_from_dict = SpeechResolution.from_dict(speech_resolution_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


