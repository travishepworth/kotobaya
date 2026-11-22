# ReviewLoopUntilStopParts

ADR-049 (additive), amended by ADR-054 (C-34). The numbers behind the review header, Home and the later-today hint (core `LoopState.untilStopParts`; client rule C3): `learning + review + new = until_stop`, always; `later` is information for the later-today hint only and is **not** summed into `until_stop` (nor shown as a header chip or in Home's big number). Depends on `keep_going` exactly as `until_stop` does (`review`, `new`). Unaffected by `kinds`. Sent by every server and local API implementing ADR-049; absent (older) → clients show `ready.learning` and `ready.review`, with `later_today.count` in the hint. Example (goal 40 reached, 9 learning cards ready, 14 on their timers, no reviews): `{learning: 9, later: 14, review: 0, new: 0}`, `until_stop` 9 (header \"9 learning\", hint \"14 later today\"). 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**later** | **int** | Learning/relearning cards on their timer that come back before &#x60;day_ends_at&#x60;. Always equal to &#x60;later_today.count&#x60;. Not part of &#x60;until_stop&#x60; (ADR-054); shown only in the later-today hint. | 
**learning** | **int** | Learning/relearning cards that can be answered now. Always equal to &#x60;ready.learning&#x60;. | 
**new** | **int** | New cards still to introduce in this mode. &#x60;keep_going&#x3D;false&#x60;: left to today&#39;s goal (the smaller of the goal&#39;s remainder and what the decks can supply under their limits; kanji cards and deck entries not yet materialized included; not bounded by &#x60;limit&#x60;); 0 once the goal is reached, also after a keep-going batch that was left unfinished (ADR-052). &#x60;keep_going&#x3D;true&#x60; without &#x60;keep_going_new_target&#x60;: the new cards this response drew (about &#x60;limit&#x60;). &#x60;keep_going&#x3D;true&#x60; with it (C-31): the new cards left to the target (not bounded by &#x60;limit&#x60;). | 
**review** | **int** | Reviews still to answer in this mode: &#x60;ready.review&#x60; for &#x60;keep_going&#x3D;false&#x60;, &#x60;backlog.reviews&#x60; for &#x60;keep_going&#x3D;true&#x60;. | 

## Example

```python
from kotobaya_client.models.review_loop_until_stop_parts import ReviewLoopUntilStopParts

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopUntilStopParts from a JSON string
review_loop_until_stop_parts_instance = ReviewLoopUntilStopParts.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopUntilStopParts.to_json())

# convert the object into a dict
review_loop_until_stop_parts_dict = review_loop_until_stop_parts_instance.to_dict()
# create an instance of ReviewLoopUntilStopParts from a dict
review_loop_until_stop_parts_from_dict = ReviewLoopUntilStopParts.from_dict(review_loop_until_stop_parts_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


