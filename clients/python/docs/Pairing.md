# Pairing

A one-time pairing code.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**code** | **str** | 8 characters of Crockford base32 (no I, L, O, U). Compared case-insensitively; hyphens and spaces typed by the user are ignored. | 
**expires_at** | **datetime** | Creation time + 10 minutes. | 
**pairing_uri** | **str** | &#x60;kotobaya://pair?server&#x3D;&lt;percent-encoded public base URL including /api/v1&gt;&amp;code&#x3D;&lt;code&gt;&#x60;; the QR code content. | 

## Example

```python
from kotobaya_client.models.pairing import Pairing

# TODO update the JSON string below
json = "{}"
# create an instance of Pairing from a JSON string
pairing_instance = Pairing.from_json(json)
# print the JSON string representation of the object
print(Pairing.to_json())

# convert the object into a dict
pairing_dict = pairing_instance.to_dict()
# create an instance of Pairing from a dict
pairing_from_dict = Pairing.from_dict(pairing_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


