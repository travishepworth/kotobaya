# CoverageCurveInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**known_pct** | **float** | Token coverage after learning them. | 
**words** | **int** | Number of top unknown words learned. | 

## Example

```python
from kotobaya_client.models.coverage_curve_inner import CoverageCurveInner

# TODO update the JSON string below
json = "{}"
# create an instance of CoverageCurveInner from a JSON string
coverage_curve_inner_instance = CoverageCurveInner.from_json(json)
# print the JSON string representation of the object
print(CoverageCurveInner.to_json())

# convert the object into a dict
coverage_curve_inner_dict = coverage_curve_inner_instance.to_dict()
# create an instance of CoverageCurveInner from a dict
coverage_curve_inner_from_dict = CoverageCurveInner.from_dict(coverage_curve_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


