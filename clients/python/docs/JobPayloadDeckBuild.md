# JobPayloadDeckBuild

Payload of a `deck-build` job (builds the item snapshot of a list deck; `contracts/db/VOCAB_LISTS.md` 4.4). Enqueued by the server only.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck_id** | **str** | The list deck. | 

## Example

```python
from kotobaya_client.models.job_payload_deck_build import JobPayloadDeckBuild

# TODO update the JSON string below
json = "{}"
# create an instance of JobPayloadDeckBuild from a JSON string
job_payload_deck_build_instance = JobPayloadDeckBuild.from_json(json)
# print the JSON string representation of the object
print(JobPayloadDeckBuild.to_json())

# convert the object into a dict
job_payload_deck_build_dict = job_payload_deck_build_instance.to_dict()
# create an instance of JobPayloadDeckBuild from a dict
job_payload_deck_build_from_dict = JobPayloadDeckBuild.from_dict(job_payload_deck_build_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


