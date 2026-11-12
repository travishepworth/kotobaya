# CoverageSegmentsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**known_pct** | **float** | Token coverage of the segment. | 
**ordinal** | **int** | 0-based order within the media. | 
**segment_id** | **str** | Segment id. | 
**title** | **str** | Segment title. | 
**token_total** | **int** | Tokens in the segment. | 
**unique_total** | **int** | Distinct entries in the segment. | 
**unique_unknown** | **int** | Of those, unknown. | 

## Example

```python
from kotobaya_client.models.coverage_segments_inner import CoverageSegmentsInner

# TODO update the JSON string below
json = "{}"
# create an instance of CoverageSegmentsInner from a JSON string
coverage_segments_inner_instance = CoverageSegmentsInner.from_json(json)
# print the JSON string representation of the object
print(CoverageSegmentsInner.to_json())

# convert the object into a dict
coverage_segments_inner_dict = coverage_segments_inner_instance.to_dict()
# create an instance of CoverageSegmentsInner from a dict
coverage_segments_inner_from_dict = CoverageSegmentsInner.from_dict(coverage_segments_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


