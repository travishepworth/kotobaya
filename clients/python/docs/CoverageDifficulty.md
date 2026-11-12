# CoverageDifficulty

Difficulty estimate relative to your other parsed media; null for non-media decks, for single segments and when nothing is parsed.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**of** | **int** | Number of media ranked. | 
**rank** | **int** | 1 &#x3D; easiest of your media by &#x60;unknown_per_1000&#x60;. | 
**unknown_per_1000** | **float** | Unknown tokens per 1000 tokens. | 

## Example

```python
from kotobaya_client.models.coverage_difficulty import CoverageDifficulty

# TODO update the JSON string below
json = "{}"
# create an instance of CoverageDifficulty from a JSON string
coverage_difficulty_instance = CoverageDifficulty.from_json(json)
# print the JSON string representation of the object
print(CoverageDifficulty.to_json())

# convert the object into a dict
coverage_difficulty_dict = coverage_difficulty_instance.to_dict()
# create an instance of CoverageDifficulty from a dict
coverage_difficulty_from_dict = CoverageDifficulty.from_dict(coverage_difficulty_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


