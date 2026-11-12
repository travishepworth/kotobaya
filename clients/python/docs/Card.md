# Card

A card (`app.card`). The schedule properties are a cache computed from the review log.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**buried_until** | **datetime** | Manual bury - hidden while now is before this instant. Sibling burying is not stored. | 
**created_at** | **datetime** | Creation time. | 
**deleted_at** | **datetime** | Tombstone. | 
**difficulty** | **float** | FSRS difficulty; null for new cards. | 
**due_at** | **datetime** | When the card becomes due; null iff &#x60;state&#x60; is &#x60;new&#x60;. | 
**flags** | **int** | 0 &#x3D; none, 1-7 &#x3D; flag colour. | 
**id** | **str** | Card id. | 
**interval_days** | **int** | Interval scheduled by the last review (0 during learning steps); null for new cards. | 
**kind** | **str** | Card kind - a key of the note type&#39;s &#x60;templates&#x60;. | 
**lapses** | **int** | Number of Again ratings while in state &#x60;review&#x60;. | 
**last_review_at** | **datetime** | Time of the last schedule-feeding review. | 
**leech** | **bool** | Derived - &#x60;lapses &gt;&#x3D; scheduler.leech_threshold&#x60;. | 
**merged_into_id** | **str** | Set on a merge loser - the survivor&#39;s id. | 
**note** | [**NoteSummary**](NoteSummary.md) | Summary of the owning note. Omitted when the card is embedded in a &#x60;Note&#x60;. | [optional] 
**note_id** | **str** | Owning note. | 
**preset_id** | **str** | Scheduling preset. If it is deleted, the Default preset schedules the card. | 
**reps** | **int** | Number of scheduled/learning reviews. | 
**stability** | **float** | FSRS stability in days; null for new cards. | 
**state** | [**CardState**](CardState.md) |  | 
**step** | **int** | Index into the (re)learning steps while learning/relearning; else null. | 
**suspended** | **bool** | Suspended cards are never queued but keep their schedule. | 
**updated_at** | **datetime** | Last change of a user register (not of the schedule cache). | 

## Example

```python
from kotobaya_client.models.card import Card

# TODO update the JSON string below
json = "{}"
# create an instance of Card from a JSON string
card_instance = Card.from_json(json)
# print the JSON string representation of the object
print(Card.to_json())

# convert the object into a dict
card_dict = card_instance.to_dict()
# create an instance of Card from a dict
card_from_dict = Card.from_dict(card_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


