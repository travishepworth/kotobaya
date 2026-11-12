# DictReleaseArtifactsInner


## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | Lowercase hex SHA-256 of a blob&#39;s bytes. | 
**from_release** | **int** | For &#x60;diff&#x60; - the release id it upgrades from; null for &#x60;full&#x60;. | 
**kind** | **str** | Full build or diff from an older release. | 
**scope** | **str** | Which entries the build holds. | 
**size_bytes** | **int** | Size of the blob. | 

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


