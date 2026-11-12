# MediaImportRequestOptions

Parser options.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**cut_audio** | **bool** | Cut per-sentence audio clips from &#x60;audio_blob_hash&#x60; files. | [optional] [default to False]
**ruby** | **str** | What to do with furigana in EPUB/HTML - drop it, or use it as a reading hint. | [optional] [default to 'hint']

## Example

```python
from kotobaya_client.models.media_import_request_options import MediaImportRequestOptions

# TODO update the JSON string below
json = "{}"
# create an instance of MediaImportRequestOptions from a JSON string
media_import_request_options_instance = MediaImportRequestOptions.from_json(json)
# print the JSON string representation of the object
print(MediaImportRequestOptions.to_json())

# convert the object into a dict
media_import_request_options_dict = media_import_request_options_instance.to_dict()
# create an instance of MediaImportRequestOptions from a dict
media_import_request_options_from_dict = MediaImportRequestOptions.from_dict(media_import_request_options_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


