# CoverageTokens

Occurrence-weighted counts.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**known** | **int** | Tokens of known entries. | 
**known_pct** | **float** | &#x60;known / total * 100&#x60;, rounded to 2 decimals; 0 when &#x60;total&#x60; is 0. | 
**learning** | **int** | Tokens of learning entries. | 
**total** | **int** | &#x60;known + learning + unknown + unmapped&#x60;. | 
**unknown** | **int** | Tokens of unknown entries. | 
**unmapped** | **int** | Tokens that matched no entry (0 when &#x60;unmapped&#x60; is hidden). | 

## Example

```python
from kotobaya_client.models.coverage_tokens import CoverageTokens

# TODO update the JSON string below
json = "{}"
# create an instance of CoverageTokens from a JSON string
coverage_tokens_instance = CoverageTokens.from_json(json)
# print the JSON string representation of the object
print(CoverageTokens.to_json())

# convert the object into a dict
coverage_tokens_dict = coverage_tokens_instance.to_dict()
# create an instance of CoverageTokens from a dict
coverage_tokens_from_dict = CoverageTokens.from_dict(coverage_tokens_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


