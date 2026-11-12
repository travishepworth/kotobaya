# RetentionGroup

Retention of one group.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**key** | **str** | Group key: &#x60;&lt;base_type&gt;/&lt;card kind&gt;&#x60; (e.g. &#x60;vocab/meaning&#x60;), a study day &#x60;YYYY-MM-DD&#x60;, an ISO week &#x60;YYYY-Www&#x60;, or a month &#x60;YYYY-MM&#x60;; &#x60;all&#x60; for the overall figure. | 
**mature_passed** | **int** | Of those, passed. | 
**mature_total** | **int** | Reviews of cards whose interval was 21 days or more. | 
**passed** | **int** | Of those, rated 2-4. | 
**retention** | **float** | &#x60;passed / total&#x60;; null when &#x60;total&#x60; is 0. | 
**total** | **int** | Scheduled reviews in the group. | 
**young_passed** | **int** | Of those, passed. | 
**young_total** | **int** | Reviews of cards whose interval was under 21 days. | 

## Example

```python
from kotobaya_client.models.retention_group import RetentionGroup

# TODO update the JSON string below
json = "{}"
# create an instance of RetentionGroup from a JSON string
retention_group_instance = RetentionGroup.from_json(json)
# print the JSON string representation of the object
print(RetentionGroup.to_json())

# convert the object into a dict
retention_group_dict = retention_group_instance.to_dict()
# create an instance of RetentionGroup from a dict
retention_group_from_dict = RetentionGroup.from_dict(retention_group_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


