# OverrideToken

One replacement token of a parse override. How the parser turns it into a token is `contracts/api/PARSER.md` 4.4.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**var_class** | **str** | Optional (C-12). Coverage class for the resulting token, e.g. &#x60;name&#x60; to hide a name the parser missed. Default - derived from the token&#39;s POS and &#x60;seq&#x60; (PARSER.md 2). | [optional] 
**lemma** | **str** | Dictionary form. Default - the lemma of a parser token with exactly this span, else the surface (PARSER.md 4.4). | [optional] 
**reading** | **str** | Optional (C-12). Hiragana reading for the resulting token. Default - derived (PARSER.md 4.4). | [optional] 
**seq** | **int** | Entry to map to; null &#x3D; force \&quot;no entry\&quot;. Never re-matched by the parser. | 
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


