# ReviewAnswerRequest

An answer to a card.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**button** | [**ReviewButton**](ReviewButton.md) | Button pressed; must map to &#x60;rating&#x60;. | [optional] 
**card_id** | **str** | The card answered. | 
**duration_ms** | **int** | Time from card shown to answer; clamped to 600000. | [optional] 
**id** | **str** | Client-generated UUIDv7 of the review (&#x60;review_log.id&#x60;); minted once per answer and reused on retry, which makes the call idempotent. | 
**mode** | **str** | &#x60;schedule&#x60; feeds FSRS; &#x60;drill&#x60; and &#x60;cram&#x60; only log. | [optional] [default to 'schedule']
**rating** | **int** | 1 Again, 2 Hard, 3 Good, 4 Easy. | 
**reviewed_at** | **datetime** | When the answer was given. Default - now. Must be at most 5 minutes after now, not before 2020-01-01, and not more than 24 h before &#x60;min(card.created_at, now)&#x60;; otherwise &#x60;422&#x60; (see &#x60;answerReview&#x60;). | [optional] 
**sentence_id** | **str** | The example sentence that was shown, from the queue item (for a grammar card, &#x60;QueueItem.grammar_sentence.sentence_id&#x60;, C-15). Stored as given in &#x60;review_log.sentence_id&#x60; (soft reference, not checked); it becomes the next showing&#39;s &#x60;lastShown&#x60;, so omitting it may repeat the sentence. | [optional] 
**utc_offset_min** | **int** | Reviewer&#39;s UTC offset in minutes (540 &#x3D; JST). | [optional] 

## Example

```python
from kotobaya_client.models.review_answer_request import ReviewAnswerRequest

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewAnswerRequest from a JSON string
review_answer_request_instance = ReviewAnswerRequest.from_json(json)
# print the JSON string representation of the object
print(ReviewAnswerRequest.to_json())

# convert the object into a dict
review_answer_request_dict = review_answer_request_instance.to_dict()
# create an instance of ReviewAnswerRequest from a dict
review_answer_request_from_dict = ReviewAnswerRequest.from_dict(review_answer_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


