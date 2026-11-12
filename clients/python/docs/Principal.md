# Principal

The credential making the request.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**device_id** | **str** | Device the token is bound to, or null. | 
**expires_at** | **datetime** | Expiry, or null for never. | 
**kind** | [**TokenKind**](TokenKind.md) |  | 
**name** | **str** | Audit name of the token. | 
**scopes** | [**List[Scope]**](Scope.md) | Scopes held, in the order read, write, review, import, admin. | 
**token_id** | **str** | &#x60;api_token.id&#x60;. | 
**totp_enabled** | **bool** | Whether the server has TOTP configured. | 

## Example

```python
from kotobaya_client.models.principal import Principal

# TODO update the JSON string below
json = "{}"
# create an instance of Principal from a JSON string
principal_instance = Principal.from_json(json)
# print the JSON string representation of the object
print(Principal.to_json())

# convert the object into a dict
principal_dict = principal_instance.to_dict()
# create an instance of Principal from a dict
principal_from_dict = Principal.from_dict(principal_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


