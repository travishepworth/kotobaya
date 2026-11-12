# ParseOverridePut

The whole override (replaces any existing row with the id).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**match_text** | **str** | Exact surface substring to re-tokenise. | 
**media_id** | **str** | Scope - absent or null &#x3D; all media. | [optional] 
**note** | **str** | Free-form remark. | [optional] [default to '']
**sentence_id** | **str** | Only this sentence (requires &#x60;media_id&#x60;). | [optional] 
**tokens** | [**List[OverrideToken]**](OverrideToken.md) | Replacement tokens. | 

## Example

```python
from kotobaya_client.models.parse_override_put import ParseOverridePut

# TODO update the JSON string below
json = "{}"
# create an instance of ParseOverridePut from a JSON string
parse_override_put_instance = ParseOverridePut.from_json(json)
# print the JSON string representation of the object
print(ParseOverridePut.to_json())

# convert the object into a dict
parse_override_put_dict = parse_override_put_instance.to_dict()
# create an instance of ParseOverridePut from a dict
parse_override_put_from_dict = ParseOverridePut.from_dict(parse_override_put_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


