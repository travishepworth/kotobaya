# DictRelease

The current dictionary release (`ref.dict_release`). The artifact formats (zstd SQLite package, `kotobaya-dict-diff` diff), what `blob_hash` covers and how a device installs or updates are defined in `contracts/db/DICT_PACKAGE.md`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**artifacts** | [**List[DictReleaseArtifactsInner]**](DictReleaseArtifactsInner.md) | Downloadable device builds of this release. | 
**built_at** | **datetime** | Build time. | 
**freq_lists** | [**List[DictReleaseFreqListsInner]**](DictReleaseFreqListsInner.md) | Loaded frequency lists by priority (0 &#x3D; primary). | 
**id** | **int** | Release number; higher &#x3D; newer. | 
**loaded_at** | **datetime** | When it was loaded into Postgres. | 
**sources** | **Dict[str, object]** | Provenance per source for the attribution page: &#x60;{\&quot;jmdict\&quot;: {\&quot;version\&quot;, \&quot;license\&quot;, \&quot;url\&quot;}, ...}&#x60;. | 
**version** | **str** | Human-readable version. | 

## Example

```python
from kotobaya_client.models.dict_release import DictRelease

# TODO update the JSON string below
json = "{}"
# create an instance of DictRelease from a JSON string
dict_release_instance = DictRelease.from_json(json)
# print the JSON string representation of the object
print(DictRelease.to_json())

# convert the object into a dict
dict_release_dict = dict_release_instance.to_dict()
# create an instance of DictRelease from a dict
dict_release_from_dict = DictRelease.from_dict(dict_release_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


