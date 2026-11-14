# JobResultDictRefresh

Result of a dictionary refresh.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**entries** | **int** | Live entries in the current release. | 
**release_id** | **int** | New release id; null when nothing changed and &#x60;force&#x60; was false. | 
**retired** | **int** | Entries retired by this release. | 
**version** | **str** | New release version. | 
**vocab_lists** | [**List[VocabListReport]**](VocabListReport.md) | C-21: per loaded vocabulary list of the new release, ordered by family then ordinal (&#x60;jlpt-n5&#x60; first): how its source rows were accounted for (&#x60;source_rows &#x3D; entries + duplicates + shadowed + unmatched&#x60;; &#x60;contracts/db/VOCAB_LISTS.md&#x60; 3.3). Empty when no list is configured; absent when no release was built. | [optional] 

## Example

```python
from kotobaya_client.models.job_result_dict_refresh import JobResultDictRefresh

# TODO update the JSON string below
json = "{}"
# create an instance of JobResultDictRefresh from a JSON string
job_result_dict_refresh_instance = JobResultDictRefresh.from_json(json)
# print the JSON string representation of the object
print(JobResultDictRefresh.to_json())

# convert the object into a dict
job_result_dict_refresh_dict = job_result_dict_refresh_instance.to_dict()
# create an instance of JobResultDictRefresh from a dict
job_result_dict_refresh_from_dict = JobResultDictRefresh.from_dict(job_result_dict_refresh_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


