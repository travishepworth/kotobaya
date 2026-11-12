# DictReleaseArtifactsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | Lowercase hex SHA-256 of a blob&#39;s bytes. | 
**format_version** | **int** | For &#x60;full&#x60; - the package&#39;s &#x60;PRAGMA user_version&#x60;; for &#x60;diff&#x60; - the &#x60;kotobaya-dict-diff&#x60; &#x60;format_version&#x60;. Currently 1. Devices skip artifacts whose version they do not support (&#x60;contracts/db/DICT_PACKAGE.md&#x60; 5-6). | 
**from_release** | **int** | For &#x60;diff&#x60; - the release id it upgrades from; null for &#x60;full&#x60;. | 
**kind** | **str** | Full build or diff from an older release. | 
**scope** | **str** | Which entries the build holds. | 
**size_bytes** | **int** | Size of the blob (compressed bytes, as served by &#x60;GET /blobs/{hash}&#x60;). | 
**uncompressed_size_bytes** | **int** | Size after zstd decompression, for the device&#39;s free-space check. | 

## Example

```python
from kotobaya_client.models.dict_release_artifacts_inner import DictReleaseArtifactsInner

# TODO update the JSON string below
json = "{}"
# create an instance of DictReleaseArtifactsInner from a JSON string
dict_release_artifacts_inner_instance = DictReleaseArtifactsInner.from_json(json)
# print the JSON string representation of the object
print(DictReleaseArtifactsInner.to_json())

# convert the object into a dict
dict_release_artifacts_inner_dict = dict_release_artifacts_inner_instance.to_dict()
# create an instance of DictReleaseArtifactsInner from a dict
dict_release_artifacts_inner_from_dict = DictReleaseArtifactsInner.from_dict(dict_release_artifacts_inner_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


