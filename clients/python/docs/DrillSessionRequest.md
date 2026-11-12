# DrillSessionRequest

What to drill.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**card_ids** | **List[str]** | &#x60;cards&#x60; only (required there). | [optional] 
**entry_seq** | **int** | &#x60;confusables&#x60; only - restrict to pairs of this entry&#39;s note. | [optional] 
**limit** | **int** | Maximum number of cards. | [optional] [default to 20]
**mode** | **str** | Review type the answers will be logged with. | [optional] [default to 'drill']
**query** | **str** | &#x60;query&#x60; only (required there) - card search string. | [optional] 
**source** | **str** | Where the cards come from. | 

## Example

```python
from kotobaya_client.models.drill_session_request import DrillSessionRequest

# TODO update the JSON string below
json = "{}"
# create an instance of DrillSessionRequest from a JSON string
drill_session_request_instance = DrillSessionRequest.from_json(json)
# print the JSON string representation of the object
print(DrillSessionRequest.to_json())

# convert the object into a dict
drill_session_request_dict = drill_session_request_instance.to_dict()
# create an instance of DrillSessionRequest from a dict
drill_session_request_from_dict = DrillSessionRequest.from_dict(drill_session_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


