# AnkiConnectResponse

AnkiConnect response envelope; exactly one of the two is non-null (both null never happens; `result` may legitimately be `false`, `0` or an empty array).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**error** | **str** | Error message, or null on success. | 
**result** | **object** | Action result (type by action), or null on error. | 

## Example

```python
from kotobaya_client.models.anki_connect_response import AnkiConnectResponse

# TODO update the JSON string below
json = "{}"
# create an instance of AnkiConnectResponse from a JSON string
anki_connect_response_instance = AnkiConnectResponse.from_json(json)
# print the JSON string representation of the object
print(AnkiConnectResponse.to_json())

# convert the object into a dict
anki_connect_response_dict = anki_connect_response_instance.to_dict()
# create an instance of AnkiConnectResponse from a dict
anki_connect_response_from_dict = AnkiConnectResponse.from_dict(anki_connect_response_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


