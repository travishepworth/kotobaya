# ReviewLoggedSchema

Appends one row to review_log. Never conflicts. OPS.md#reviewlogged

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**button** | **str** | Button pressed; must agree with rating (again/nothing/something&#x3D;1, hard&#x3D;2, good/okay&#x3D;3, easy&#x3D;4). null &#x3D; unknown, and always null for manual. Default null. | [optional] 
**card_id** | **str** | Card as it was on the reviewing device; must exist on the server (may be tombstoned or merged). Never rewritten. | 
**duration_ms** | **int** | Time from card shown to answer, capped at 600000; null &#x3D; unknown. Default null. | [optional] 
**id** | **str** | review_log.id (UUIDv7 from the reviewing client). Equals entity_id. Distinct from op_id. | 
**import_source** | **str** | Server-emitted import ops only: &#39;jpdb&#39;. Clients MUST omit it or send null. | [optional] 
**manual_action** | **str** | Required (non-null) iff review_type &#x3D; manual. set_due: keep memory state, due :&#x3D; manual_due_at. reset: card returns to state new. | [optional] 
**manual_due_at** | **int** | Non-null iff manual_action &#x3D; set_due. | [optional] 
**rating** | **int** | FSRS rating 1 Again, 2 Hard, 3 Good, 4 Easy. null iff review_type &#x3D; manual. | 
**replay_hlc** | **str** | Server-emitted import ops only: the value stored in review_log.hlc (replay position), synthesised as &lt;historical ms&gt;-&lt;running counter&gt;-&lt;server device id&gt;. When absent/null, review_log.hlc &#x3D; the op&#39;s hlc. Requires import_source. | [optional] 
**review_type** | **str** | scheduled, learning, manual feed the schedule (replay set); drill, cram are logged only. | 
**reviewed_at** | **int** | Wall-clock instant of the answer on the reviewing device. | 
**sentence_id** | **str** | Sentence shown for rotating-example cards (soft reference). Default null. | [optional] 
**utc_offset_min** | **int** | Device UTC offset in minutes at review time (540 &#x3D; JST); null &#x3D; unknown. Native clients MUST send a number. | 

## Example

```python
from kotobaya_client.models.review_logged_schema import ReviewLoggedSchema

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoggedSchema from a JSON string
review_logged_schema_instance = ReviewLoggedSchema.from_json(json)
# print the JSON string representation of the object
print(ReviewLoggedSchema.to_json())

# convert the object into a dict
review_logged_schema_dict = review_logged_schema_instance.to_dict()
# create an instance of ReviewLoggedSchema from a dict
review_logged_schema_from_dict = ReviewLoggedSchema.from_dict(review_logged_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


