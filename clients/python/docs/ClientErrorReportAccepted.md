# ClientErrorReportAccepted

Answer of `POST /client-errors`.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**accepted** | **int** | Number of items logged (always the number sent). | 

## Example

```python
from kotobaya_client.models.client_error_report_accepted import ClientErrorReportAccepted

# TODO update the JSON string below
json = "{}"
# create an instance of ClientErrorReportAccepted from a JSON string
client_error_report_accepted_instance = ClientErrorReportAccepted.from_json(json)
# print the JSON string representation of the object
print(ClientErrorReportAccepted.to_json())

# convert the object into a dict
client_error_report_accepted_dict = client_error_report_accepted_instance.to_dict()
# create an instance of ClientErrorReportAccepted from a dict
client_error_report_accepted_from_dict = ClientErrorReportAccepted.from_dict(client_error_report_accepted_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


