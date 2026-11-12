# TokenCreated

A new token including its secret.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**info** | [**Token**](Token.md) |  | 
**token** | **str** | The token string. Shown only here. | 

## Example

```python
from kotobaya_client.models.token_created import TokenCreated

# TODO update the JSON string below
json = "{}"
# create an instance of TokenCreated from a JSON string
token_created_instance = TokenCreated.from_json(json)
# print the JSON string representation of the object
print(TokenCreated.to_json())

# convert the object into a dict
token_created_dict = token_created_instance.to_dict()
# create an instance of TokenCreated from a dict
token_created_from_dict = TokenCreated.from_dict(token_created_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


