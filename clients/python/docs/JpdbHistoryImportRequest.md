# JpdbHistoryImportRequest

jpdb export to import. Also the payload of `jpdb-history-import` jobs.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**blob_hash** | **str** | The uploaded jpdb review export (JSON). | 
**import_reviews** | **bool** | Import review history (creates notes and cards as needed). | [optional] [default to True]
**import_word_status** | **bool** | Import known, never-forget and blacklisted words as overrides. | [optional] [default to True]

## Example

```python
from kotobaya_client.models.jpdb_history_import_request import JpdbHistoryImportRequest

# TODO update the JSON string below
json = "{}"
# create an instance of JpdbHistoryImportRequest from a JSON string
jpdb_history_import_request_instance = JpdbHistoryImportRequest.from_json(json)
# print the JSON string representation of the object
print(JpdbHistoryImportRequest.to_json())

# convert the object into a dict
jpdb_history_import_request_dict = jpdb_history_import_request_instance.to_dict()
# create an instance of JpdbHistoryImportRequest from a dict
jpdb_history_import_request_from_dict = JpdbHistoryImportRequest.from_dict(jpdb_history_import_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


