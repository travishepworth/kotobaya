# MediaImportRequest

What to import (see `POST /media/import`). Also the payload of `media-import` jobs, with `media_id` always set and `import_id` omitted (it is the job's `id`).

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**deck** | [**MediaDeckOptions**](MediaDeckOptions.md) |  | [optional] 
**files** | [**List[MediaImportFile]**](MediaImportFile.md) | &#x60;file&#x60; only (required). | [optional] 
**format** | **str** | Source format. &#x60;auto&#x60; decides by file extension (&#x60;file&#x60;), is &#x60;html&#x60; for &#x60;url&#x60; and &#x60;txt&#x60; for &#x60;paste&#x60;. | [optional] [default to 'auto']
**import_id** | **str** | Optional idempotency key (UUIDv7) - becomes the job id and, for new media, the media id. A retry with the same value returns the existing job (&#x60;200&#x60;) and enqueues nothing. | [optional] 
**kind** | [**MediaKind**](MediaKind.md) |  | [optional] 
**media_id** | **str** | Existing media to append to. Absent - create a new media. | [optional] 
**options** | [**MediaImportRequestOptions**](MediaImportRequestOptions.md) |  | [optional] 
**source** | **str** | Where the content comes from. | 
**text** | **str** | &#x60;paste&#x60; only (required). | [optional] 
**title** | **str** | Title of the new media. | [optional] 
**url** | **str** | &#x60;url&#x60; only (required) - http or https. | [optional] 

## Example

```python
from kotobaya_client.models.media_import_request import MediaImportRequest

# TODO update the JSON string below
json = "{}"
# create an instance of MediaImportRequest from a JSON string
media_import_request_instance = MediaImportRequest.from_json(json)
# print the JSON string representation of the object
print(MediaImportRequest.to_json())

# convert the object into a dict
media_import_request_dict = media_import_request_instance.to_dict()
# create an instance of MediaImportRequest from a dict
media_import_request_from_dict = MediaImportRequest.from_dict(media_import_request_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


