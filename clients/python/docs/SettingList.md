# SettingList

Stored settings.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**items** | [**List[Setting]**](Setting.md) | Settings ordered by key. | 

## Example

```python
from kotobaya_client.models.setting_list import SettingList

# TODO update the JSON string below
json = "{}"
# create an instance of SettingList from a JSON string
setting_list_instance = SettingList.from_json(json)
# print the JSON string representation of the object
print(SettingList.to_json())

# convert the object into a dict
setting_list_dict = setting_list_instance.to_dict()
# create an instance of SettingList from a dict
setting_list_from_dict = SettingList.from_dict(setting_list_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


