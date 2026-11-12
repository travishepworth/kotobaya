
# MediaImportFile

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **blobHash** | **kotlin.String** | The uploaded file. |  |
| **filename** | **kotlin.String** | Original file name; its extension selects the parser when &#x60;format&#x60; is &#x60;auto&#x60;. |  |
| **audioBlobHash** | **kotlin.String** | Optional uploaded video or audio file for this subtitle file; with &#x60;options.cut_audio&#x60; the worker cuts one clip per sentence. |  [optional] |
| **title** | **kotlin.String** | Segment title. Default - the file name without extension. |  [optional] |



