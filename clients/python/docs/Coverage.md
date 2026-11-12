# Coverage

Coverage numbers. Entries are classified by effective word status after removing the classes named in `hide`. `unique` counts distinct entries; `tokens` weights each entry by its occurrence count and is the number that predicts comprehension. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**computed_at** | **datetime** | When the numbers were computed. | 
**curve** | [**List[CoverageCurveInner]**](CoverageCurveInner.md) | \&quot;Learn the top N unknown words to reach X%\&quot;: points for N &#x3D; 0, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000 (those below the number of unknown entries) and a final point for all of them. Unknown entries are taken in descending occurrence count, ties by &#x60;seq&#x60;. | 
**deck_id** | **str** | The deck measured (equals the media id for media). | 
**difficulty** | [**CoverageDifficulty**](CoverageDifficulty.md) |  | 
**hide** | **List[str]** | Classes that were left out. | 
**ignored_count** | **int** | Distinct entries with status &#x60;blacklisted&#x60; or &#x60;ignored&#x60; (reported whether hidden or not). | 
**segment_id** | **str** | The segment measured, or null for the whole deck or media. | 
**segments** | [**List[CoverageSegmentsInner]**](CoverageSegmentsInner.md) | Per-segment summary in &#x60;ordinal&#x60; order (empty for non-media decks). | 
**tokens** | [**CoverageTokens**](CoverageTokens.md) |  | 
**unique** | [**CoverageCounts**](CoverageCounts.md) | Distinct entries. | 

## Example

```python
from kotobaya_client.models.coverage import Coverage

# TODO update the JSON string below
json = "{}"
# create an instance of Coverage from a JSON string
coverage_instance = Coverage.from_json(json)
# print the JSON string representation of the object
print(Coverage.to_json())

# convert the object into a dict
coverage_dict = coverage_instance.to_dict()
# create an instance of Coverage from a dict
coverage_from_dict = Coverage.from_dict(coverage_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


