# JobResultDeckBuild

Result of a deck build (`contracts/db/VOCAB_LISTS.md` 4.4). `list_key`, `changed` and `unmatched` were added by C-21; every C-21 producer sets them.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**changed** | **bool** | false &#x3D; the computed items equal the installed ones; nothing was published and no op was emitted. | [optional] 
**content_version** | **int** | Content version published, or the deck&#39;s current version when &#x60;changed&#x60; is false. | 
**deck_id** | **str** | The deck. | 
**items** | **int** | Items in the snapshot (at least 1 for a C-21 build; an empty list fails the job instead). | 
**list_key** | **str** | The deck&#39;s list key. | [optional] 
**unmatched** | **int** | Source rows of the vocabulary list that matched no dictionary entry (&#x60;ref.vocab_list.unmatched_rows&#x60;; the rows are in &#x60;ref.vocab_list_unmatched&#x60;). 0 for frequency bands. | [optional] 

## Example

```python
from kotobaya_client.models.job_result_deck_build import JobResultDeckBuild

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultDeckBuild from a JSON string
job_result_deck_build_instance = JobResultDeckBuild.from_json(json)
# print the JSON string representation of the object
print(JobResultDeckBuild.to_json())

# convert the object into a dict
job_result_deck_build_dict = job_result_deck_build_instance.to_dict()
# create an instance of JobResultDeckBuild from a dict
job_result_deck_build_from_dict = JobResultDeckBuild.from_dict(job_result_deck_build_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


