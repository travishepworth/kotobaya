# TokenCreate

New personal access token.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**expires_at** | **datetime** | Optional expiry (must be in the future). Absent or null &#x3D; never. | [optional] 
**name** | **str** | Audit name shown in logs and lists. | 
**scopes** | [**List[Scope]**](Scope.md) | Scopes to grant. | 
**with_device** | **bool** | Also create a &#x60;script&#x60; device bound to the token, so it can push ops. | [optional] [default to False]

## Example

```python
from kotobaya_client.models.token_create import TokenCreate

# TODO update the JSON string below
json = "{}"
# create an instance of TokenCreate from a JSON string
token_create_instance = TokenCreate.from_json(json)
# print the JSON string representation of the object
print(TokenCreate.to_json())

# convert the object into a dict
token_create_dict = token_create_instance.to_dict()
# create an instance of TokenCreate from a dict
token_create_from_dict = TokenCreate.from_dict(token_create_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


