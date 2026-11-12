# Blob

Metadata of a blob (`app.blob`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**content_type** | **str** | Stored media type. | 
**created_at** | **datetime** | When it was stored. | 
**hash** | **str** | Lowercase hex SHA-256 of a blob&#39;s bytes. | 
**kind** | **str** | What the blob is. | 
**origin** | **str** | Where it came from. | 
**size_bytes** | **int** | Size. | 

## Example

```python
from kotobaya_client.models.blob import Blob

# TODO update the JSON string below
json = "{}"
# create an instance of Blob from a JSON string
blob_instance = Blob.from_json(json)
# print the JSON string representation of the object
print(Blob.to_json())

# convert the object into a dict
blob_dict = blob_instance.to_dict()
# create an instance of Blob from a dict
blob_from_dict = Blob.from_dict(blob_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


