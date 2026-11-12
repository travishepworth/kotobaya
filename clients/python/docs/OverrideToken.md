# OverrideToken

One replacement token of a parse override.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**lemma** | **str** | Dictionary form. Default - the surface. | [optional] 
**seq** | **int** | Entry to map to; null &#x3D; force \&quot;no entry\&quot;. | 
**surface** | **str** | Surface text. | 

## Example

```python
from kotobaya_client.models.override_token import OverrideToken

# TODO update the JSON string below
json = "{}"
# create an instance of OverrideToken from a JSON string
override_token_instance = OverrideToken.from_json(json)
# print the JSON string representation of the object
print(OverrideToken.to_json())

# convert the object into a dict
override_token_dict = override_token_instance.to_dict()
# create an instance of OverrideToken from a dict
override_token_from_dict = OverrideToken.from_dict(override_token_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


