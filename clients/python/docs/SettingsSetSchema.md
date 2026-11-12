# SettingsSetSchema

Writes one settings key (one register per key). OPS.md#settingsset

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**key** | **str** | Dotted lowercase key, e.g. scheduler.new_per_day. Equals entity_id. | 
**value** | **object** | Any JSON value. null &#x3D; reset to default (the row stays so the reset beats older sets). | 

## Example

```python
from kotobaya_client.models.settings_set_schema import SettingsSetSchema

# TODO update the JSON string below
json = "{}"
# create an instance of SettingsSetSchema from a JSON string
settings_set_schema_instance = SettingsSetSchema.from_json(json)
# print the JSON string representation of the object
print(SettingsSetSchema.to_json())

# convert the object into a dict
settings_set_schema_dict = settings_set_schema_instance.to_dict()
# create an instance of SettingsSetSchema from a dict
settings_set_schema_from_dict = SettingsSetSchema.from_dict(settings_set_schema_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


