# StatsSummaryDue

**Deprecated (ADR-037): use `getReviewQueue?limit=1` and its `loop`.** Still sent with the frozen meaning of the deprecated `ReviewQueue.counts` (core `LoopState.legacy`, computed through `ReviewLoop` for an unfiltered normal request): the over-counting `learning` documented there and `new` = the day's remaining new cards (not bounded by any batch). No `loop` is added here. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learning** | **int** | Learning and relearning cards due before the day ends. | 
**new** | **int** | New cards still to introduce, including deck entries the build draws that have no note yet (C-27; this endpoint never creates them). | 
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


