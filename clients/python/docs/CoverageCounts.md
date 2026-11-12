# CoverageCounts

Counts split by word status.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**known** | **int** | Status &#x60;known&#x60; or &#x60;never_forget&#x60;. | 
**learning** | **int** | Status &#x60;learning&#x60;. | 
**total** | **int** | &#x60;known + learning + unknown&#x60; (plus &#x60;unmapped&#x60; for token counts). | 
**unknown** | **int** | Status &#x60;new&#x60;; also &#x60;blacklisted&#x60;/&#x60;ignored&#x60; when &#x60;ignored&#x60; is not hidden. | 

## Example

```python
from kotobaya_client.models.coverage_counts import CoverageCounts

# TODO update the JSON string below
json = "{}"
# create an instance of CoverageCounts from a JSON string
coverage_counts_instance = CoverageCounts.from_json(json)
# print the JSON string representation of the object
print(CoverageCounts.to_json())

# convert the object into a dict
coverage_counts_dict = coverage_counts_instance.to_dict()
# create an instance of CoverageCounts from a dict
coverage_counts_from_dict = CoverageCounts.from_dict(coverage_counts_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


