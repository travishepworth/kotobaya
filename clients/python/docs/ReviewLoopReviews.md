# ReviewLoopReviews

Today's review count against the global limit.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**limit** | **int** | &#x60;scheduler.reviews_per_day&#x60;; null &#x3D; &#x60;scheduler.reviews_unlimited&#x60; is true (no limit). | 
**today** | **int** | Reviews of review-state cards answered in the current study day (undone ones excluded; keep-going ones included). | 

## Example

```python
from kotobaya_client.models.review_loop_reviews import ReviewLoopReviews

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopReviews from a JSON string
review_loop_reviews_instance = ReviewLoopReviews.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopReviews.to_json())

# convert the object into a dict
review_loop_reviews_dict = review_loop_reviews_instance.to_dict()
# create an instance of ReviewLoopReviews from a dict
review_loop_reviews_from_dict = ReviewLoopReviews.from_dict(review_loop_reviews_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


