
# MediacontentPublishedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentBlobHash** | **kotlin.String** | Snapshot blob for content_version; null iff content_version &#x3D; 0. |  |
| **contentVersion** | **kotlin.Int** | content_version after this op; 0 &#x3D; nothing published yet. Applied iff &gt;&#x3D; the stored one. |  |
| **mediaId** | **kotlin.String** | Media. Equals entity_id. |  |
| **parseStatus** | [**inline**](#ParseStatus) | New parse_status. |  |
| **parseError** | **kotlin.String** | Error text when parse_status &#x3D; failed, else null. Default null. |  [optional] |


<a id="ParseStatus"></a>
## Enum: parse_status
| Name | Value |
| ---- | ----- |
| parseStatus | pending, parsing, parsed, failed |



