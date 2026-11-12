# SettingsPatch

Settings to write.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**values** | **Dict[str, object]** | Map of setting key to new value; JSON null resets the key to its default. | 

## Example

```python
from kotobaya_client.models.settings_patch import SettingsPatch

# TODO update the JSON string below
json = "{}"
# create an instance of SettingsPatch from a JSON string
settings_patch_instance = SettingsPatch.from_json(json)
# print the JSON string representation of the object
print(SettingsPatch.to_json())

# convert the object into a dict
settings_patch_dict = settings_patch_instance.to_dict()
# create an instance of SettingsPatch from a dict
settings_patch_from_dict = SettingsPatch.from_dict(settings_patch_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


