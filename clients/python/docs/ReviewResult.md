# ReviewResult

A review and the card after it.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**card** | [**Card**](Card.md) | The card (the survivor, if the answered card was merged) with its recomputed schedule and &#x60;note&#x60;. | 
**review** | [**ReviewLog**](ReviewLog.md) |  | 

## Example

```python
from kotobaya_client.models.review_result import ReviewResult

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewResult from a JSON string
review_result_instance = ReviewResult.from_json(json)
# print the JSON string representation of the object
print(ReviewResult.to_json())

# convert the object into a dict
review_result_dict = review_result_instance.to_dict()
# create an instance of ReviewResult from a dict
review_result_from_dict = ReviewResult.from_dict(review_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


