# ParseRequest

Text to parse.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**text** | **str** | Japanese text. | 

## Example

```python
from kotobaya_client.models.parse_request import ParseRequest

# TODO update the JSON string below
json = "{}"
# create an instance of ParseRequest from a JSON string
parse_request_instance = ParseRequest.from_json(json)
# print the JSON string representation of the object
print(ParseRequest.to_json())

# convert the object into a dict
parse_request_dict = parse_request_instance.to_dict()
# create an instance of ParseRequest from a dict
parse_request_from_dict = ParseRequest.from_dict(parse_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


