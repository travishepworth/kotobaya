# BlobQueryResult

Which blobs exist.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**missing** | **List[str]** | Hashes it does not have. | 
**present** | **List[str]** | Hashes the server has. | 

## Example

```python
from kotobaya_client.models.blob_query_result import BlobQueryResult

# TODO update the JSON string below
json = "{}"
# create an instance of BlobQueryResult from a JSON string
blob_query_result_instance = BlobQueryResult.from_json(json)
# print the JSON string representation of the object
print(BlobQueryResult.to_json())

# convert the object into a dict
blob_query_result_dict = blob_query_result_instance.to_dict()
# create an instance of BlobQueryResult from a dict
blob_query_result_from_dict = BlobQueryResult.from_dict(blob_query_result_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


