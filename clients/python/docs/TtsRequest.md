# TtsRequest

Text to synthesize. Also the payload of `tts` jobs.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**text** | **str** | Japanese text (plain, no HTML). | 
**voice** | **str** | VOICEVOX speaker id or name. Default - the setting &#x60;tts.voice&#x60;, else the server default. | [optional] 

## Example

```python
from kotobaya_client.models.tts_request import TtsRequest

# TODO update the JSON string below
json = "{}"
# create an instance of TtsRequest from a JSON string
tts_request_instance = TtsRequest.from_json(json)
# print the JSON string representation of the object
print(TtsRequest.to_json())

# convert the object into a dict
tts_request_dict = tts_request_instance.to_dict()
# create an instance of TtsRequest from a dict
tts_request_from_dict = TtsRequest.from_dict(tts_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


