# ReviewLog

A row of `app.review_log`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**button** | [**ReviewButton**](ReviewButton.md) | Button pressed; null when unknown or manual. | 
**card_id** | **str** | Card as addressed when the review was logged (never rewritten after merges). | 
**device_id** | **str** | Device that logged it; the server device for REST reviews and imports. | 
**duration_ms** | **int** | Time from card shown to answer. | 
**hlc** | **str** | Replay position. | 
**id** | **str** | Review id. | 
**import_source** | **str** | &#x60;jpdb&#x60; for imported history; null for native reviews. | 
**manual_action** | **str** | For &#x60;manual&#x60; rows. | 
**manual_due_at** | **datetime** | For &#x60;set_due&#x60;. | 
**rating** | **int** | 1 Again, 2 Hard, 3 Good, 4 Easy; null iff &#x60;review_type&#x60; is &#x60;manual&#x60;. | 
**review_type** | [**ReviewType**](ReviewType.md) |  | 
**reviewed_at** | **datetime** | Wall-clock time of the review. | 
**sentence_id** | **str** | Example sentence shown (rotating-example cards). | 
**undone_at** | **datetime** | Set when the review was undone; undone rows are ignored by replay and stats. | 
**utc_offset_min** | **int** | Reviewer&#39;s UTC offset in minutes; null &#x3D; unknown. | 

## Example

```python
from kotobaya_client.models.review_log import ReviewLog

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLog from a JSON string
review_log_instance = ReviewLog.from_json(json)
# print the JSON string representation of the object
print(ReviewLog.to_json())

# convert the object into a dict
review_log_dict = review_log_instance.to_dict()
# create an instance of ReviewLog from a dict
review_log_from_dict = ReviewLog.from_dict(review_log_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


