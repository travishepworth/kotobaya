# StatsSummaryDue

Remaining today under the current limits (same as `ReviewQueue.counts`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learning** | **int** | Learning and relearning cards due before the day ends. | 
**new** | **int** | New cards still to introduce. | 
**review** | **int** | Review cards due. | 

## Example

```python
from kotobaya_client.models.stats_summary_due import StatsSummaryDue

# TODO update the JSON string below
json = "{}"
# create an instance of StatsSummaryDue from a JSON string
stats_summary_due_instance = StatsSummaryDue.from_json(json)
# print the JSON string representation of the object
print(StatsSummaryDue.to_json())

# convert the object into a dict
stats_summary_due_dict = stats_summary_due_instance.to_dict()
# create an instance of StatsSummaryDue from a dict
stats_summary_due_from_dict = StatsSummaryDue.from_dict(stats_summary_due_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


