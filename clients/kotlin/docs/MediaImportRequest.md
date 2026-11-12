
# MediaImportRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **source** | [**inline**](#Source) | Where the content comes from. |  |
| **deck** | [**MediaDeckOptions**](MediaDeckOptions.md) |  |  [optional] |
| **files** | [**kotlin.collections.List&lt;MediaImportFile&gt;**](MediaImportFile.md) | &#x60;file&#x60; only (required). |  [optional] |
| **format** | [**inline**](#Format) | Source format. &#x60;auto&#x60; decides by file extension (&#x60;file&#x60;), is &#x60;html&#x60; for &#x60;url&#x60; and &#x60;txt&#x60; for &#x60;paste&#x60;. |  [optional] |
| **importId** | **kotlin.String** | Optional idempotency key (UUIDv7) - becomes the job id and, for new media, the media id. A retry with the same value returns the existing job (&#x60;200&#x60;) and enqueues nothing. |  [optional] |
| **kind** | [**MediaKind**](MediaKind.md) |  |  [optional] |
| **mediaId** | **kotlin.String** | Existing media to append to. Absent - create a new media. |  [optional] |
| **options** | [**MediaImportRequestOptions**](MediaImportRequestOptions.md) |  |  [optional] |
| **text** | **kotlin.String** | &#x60;paste&#x60; only (required). |  [optional] |
| **title** | **kotlin.String** | Title of the new media. |  [optional] |
| **url** | [**java.net.URI**](java.net.URI.md) | &#x60;url&#x60; only (required) - http or https. |  [optional] |


<a id="Source"></a>
## Enum: source
| Name | Value |
| ---- | ----- |
| source | file, url, paste |


<a id="Format"></a>
## Enum: format
| Name | Value |
| ---- | ----- |
| format | auto, srt, ass, vtt, epub, txt, mokuro, html, texthooker |



