# MediaImportFile

One uploaded source file.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**audio_blob_hash** | **str** | Optional uploaded video or audio file for this subtitle file; with &#x60;options.cut_audio&#x60; the worker cuts one clip per sentence. | [optional] 
**blob_hash** | **str** | The uploaded file. | 
**filename** | **str** | Original file name; its extension selects the parser when &#x60;format&#x60; is &#x60;auto&#x60;. | 
**title** | **str** | Segment title. Default - the file name without extension. | [optional] 

## Example

```python
from kotobaya_client.models.media_import_file import MediaImportFile

# TODO update the JSON string below
json = "{}"
# create an instance of MediaImportFile from a JSON string
media_import_file_instance = MediaImportFile.from_json(json)
# print the JSON string representation of the object
print(MediaImportFile.to_json())

# convert the object into a dict
media_import_file_dict = media_import_file_instance.to_dict()
# create an instance of MediaImportFile from a dict
media_import_file_from_dict = MediaImportFile.from_dict(media_import_file_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


