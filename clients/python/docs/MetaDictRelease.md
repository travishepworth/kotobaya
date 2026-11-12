# MetaDictRelease

Current dictionary release, or null when none is loaded.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**id** | **int** | Release number. | 
**version** | **str** | Human-readable version. | 

## Example

```python
from kotobaya_client.models.meta_dict_release import MetaDictRelease

# TODO update the JSON string below
json = "{}"
# create an instance of MetaDictRelease from a JSON string
meta_dict_release_instance = MetaDictRelease.from_json(json)
# print the JSON string representation of the object
print(MetaDictRelease.to_json())

# convert the object into a dict
meta_dict_release_dict = meta_dict_release_instance.to_dict()
# create an instance of MetaDictRelease from a dict
meta_dict_release_from_dict = MetaDictRelease.from_dict(meta_dict_release_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


