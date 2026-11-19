# SpeechResolveResult

The answer of `resolveSpeech`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[SpeechResolution]**](SpeechResolution.md) | One resolution per request item, in request order. | 

## Example

```python
from kotobaya_client.models.speech_resolve_result import SpeechResolveResult

# TODO update the JSON string below
json = "{}"
# create an instance of SpeechResolveResult from a JSON string
speech_resolve_result_instance = SpeechResolveResult.from_json(json)
# print the JSON string representation of the object
print(SpeechResolveResult.to_json())

# convert the object into a dict
speech_resolve_result_dict = speech_resolve_result_instance.to_dict()
# create an instance of SpeechResolveResult from a dict
speech_resolve_result_from_dict = SpeechResolveResult.from_dict(speech_resolve_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


