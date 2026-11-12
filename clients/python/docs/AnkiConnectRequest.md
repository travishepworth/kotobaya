# AnkiConnectRequest

AnkiConnect request envelope.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**action** | **str** | Supported: &#x60;version&#x60;, &#x60;deckNames&#x60;, &#x60;modelNames&#x60;, &#x60;modelFieldNames&#x60;, &#x60;addNote&#x60;, &#x60;canAddNotes&#x60;, &#x60;findNotes&#x60;, &#x60;storeMediaFile&#x60;. Any other value yields error &#x60;unsupported action&#x60;. | 
**key** | **str** | A personal access token with the &#x60;write&#x60; scope, when no other credential is sent. | [optional] 
**params** | **Dict[str, object]** | Action parameters (table on &#x60;POST /ankiconnect&#x60;). | [optional] 
**version** | **int** | Protocol version the client speaks; the server always answers in the version 6 shape. | [optional] 

## Example

```python
from kotobaya_client.models.anki_connect_request import AnkiConnectRequest

# TODO update the JSON string below
json = "{}"
# create an instance of AnkiConnectRequest from a JSON string
anki_connect_request_instance = AnkiConnectRequest.from_json(json)
# print the JSON string representation of the object
print(AnkiConnectRequest.to_json())

# convert the object into a dict
anki_connect_request_dict = anki_connect_request_instance.to_dict()
# create an instance of AnkiConnectRequest from a dict
anki_connect_request_from_dict = AnkiConnectRequest.from_dict(anki_connect_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


