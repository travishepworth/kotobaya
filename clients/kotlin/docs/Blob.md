
# Blob

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentType** | **kotlin.String** | Stored media type. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When it was stored. |  |
| **hash** | **kotlin.String** | Lowercase hex SHA-256 of a blob&#39;s bytes. |  |
| **kind** | [**inline**](#Kind) | What the blob is. |  |
| **origin** | [**inline**](#Origin) | Where it came from. |  |
| **sizeBytes** | **kotlin.Long** | Size. |  |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | audio, image, upload, snapshot |


<a id="Origin"></a>
## Enum: origin
| Name | Value |
| ---- | ----- |
| origin | upload, clip, tts, cover, build |



