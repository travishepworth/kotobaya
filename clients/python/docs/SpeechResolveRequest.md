# SpeechResolveRequest

Pairs or notes to resolve (`resolveSpeech`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[SpeechResolveItem]**](SpeechResolveItem.md) | What to resolve. The answer has one element per item, in this order. | 
**urgent** | **bool** | Run the needed jobs before all non-urgent jobs. Only with at most 20 items. | [optional] [default to False]

## Example

```python
from kotobaya_client.models.speech_resolve_request import SpeechResolveRequest

# TODO update the JSON string below
json = "{}"
# create an instance of SpeechResolveRequest from a JSON string
speech_resolve_request_instance = SpeechResolveRequest.from_json(json)
# print the JSON string representation of the object
print(SpeechResolveRequest.to_json())

# convert the object into a dict
speech_resolve_request_dict = speech_resolve_request_instance.to_dict()
# create an instance of SpeechResolveRequest from a dict
speech_resolve_request_from_dict = SpeechResolveRequest.from_dict(speech_resolve_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


