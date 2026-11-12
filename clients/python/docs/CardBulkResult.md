# CardBulkResult

Outcome of a bulk call.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**changed** | **int** | Cards (or, for tag and move actions, notes) for which an op was emitted. | 
**dry_run** | **bool** | Echo of the request flag. | 
**matched** | **int** | Live cards selected. | 
**ops_emitted** | **int** | Number of ops emitted (0 for a dry run). | 
**skipped** | **int** | Selected ids or cards for which nothing was done (unknown, deleted, merged away, already in a no-op state). | 

## Example

```python
from kotobaya_client.models.card_bulk_result import CardBulkResult

# TODO update the JSON string below
json = "{}"
# create an instance of CardBulkResult from a JSON string
card_bulk_result_instance = CardBulkResult.from_json(json)
# print the JSON string representation of the object
print(CardBulkResult.to_json())

# convert the object into a dict
card_bulk_result_dict = card_bulk_result_instance.to_dict()
# create an instance of CardBulkResult from a dict
card_bulk_result_from_dict = CardBulkResult.from_dict(card_bulk_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


