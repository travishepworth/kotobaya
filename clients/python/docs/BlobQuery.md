# BlobQuery

Hashes to look up.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**hashes** | **List[str]** | Blob hashes. | 

## Example

```python
from kotobaya_client.models.blob_query import BlobQuery

# TODO update the JSON string below
json = "{}"
# create an instance of BlobQuery from a JSON string
blob_query_instance = BlobQuery.from_json(json)
# print the JSON string representation of the object
print(BlobQuery.to_json())

# convert the object into a dict
blob_query_dict = blob_query_instance.to_dict()
# create an instance of BlobQuery from a dict
blob_query_from_dict = BlobQuery.from_dict(blob_query_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


