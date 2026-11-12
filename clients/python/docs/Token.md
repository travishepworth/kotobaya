# Token

Metadata of a credential (`app.api_token`). The token string and hash are never returned.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**active** | **bool** | Derived - valid right now (not revoked, not expired, device not revoked). | 
**created_at** | **datetime** | Creation time. | 
**device_id** | **str** | Bound device, or null. | 
**expires_at** | **datetime** | Expiry, or null for never. | 
**id** | **str** | Token id. | 
**kind** | [**TokenKind**](TokenKind.md) |  | 
**last_used_at** | **datetime** | Last authenticated request (updated at most once a minute). | 
**name** | **str** | Audit name. | 
**revoked_at** | **datetime** | Set when revoked. | 
**scopes** | [**List[Scope]**](Scope.md) | Scopes held. | 
**token_prefix** | **str** | First 8 characters of the token string. | 

## Example

```python
from kotobaya_client.models.token import Token

# TODO update the JSON string below
json = "{}"
# create an instance of Token from a JSON string
token_instance = Token.from_json(json)
# print the JSON string representation of the object
print(Token.to_json())

# convert the object into a dict
token_dict = token_instance.to_dict()
# create an instance of Token from a dict
token_from_dict = Token.from_dict(token_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


