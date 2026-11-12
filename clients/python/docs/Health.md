# Health

Liveness answer.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**status** | **str** | Always &#x60;ok&#x60; in a 200 response. | 
**time** | **datetime** | Server clock. | 
**version** | **str** | Server build version. | 

## Example

```python
from kotobaya_client.models.health import Health

# TODO update the JSON string below
json = "{}"
# create an instance of Health from a JSON string
health_instance = Health.from_json(json)
# print the JSON string representation of the object
print(Health.to_json())

# convert the object into a dict
health_dict = health_instance.to_dict()
# create an instance of Health from a dict
health_from_dict = Health.from_dict(health_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


