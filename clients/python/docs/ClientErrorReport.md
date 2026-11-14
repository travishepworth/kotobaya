# ClientErrorReport

Body of `POST /client-errors` (C-23): a batch of 1 to 20 client error reports. Limits and client obligations are in the operation. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**dropped** | **int** | Optional, default 0. Number of reports this client discarded (queue overflow, or a batch refused with a non-retryable status) since its last &#x60;202&#x60;. Logged as one WARN line when above 0.  | [optional] 
**items** | [**List[ClientErrorItem]**](ClientErrorItem.md) | The reports, in any order. More than 20 is &#x60;413 payload_too_large&#x60; (split the batch); none is &#x60;422&#x60;. | 

## Example

```python
from kotobaya_client.models.client_error_report import ClientErrorReport

# TODO update the JSON string below
json = "{}"
# create an instance of ClientErrorReport from a JSON string
client_error_report_instance = ClientErrorReport.from_json(json)
# print the JSON string representation of the object
print(ClientErrorReport.to_json())

# convert the object into a dict
client_error_report_dict = client_error_report_instance.to_dict()
# create an instance of ClientErrorReport from a dict
client_error_report_from_dict = ClientErrorReport.from_dict(client_error_report_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


