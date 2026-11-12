
# JobResultMedia

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentVersion** | **kotlin.Int** | Content version published by this job. |  |
| **mediaId** | **kotlin.String** | The media. |  |
| **segments** | **kotlin.Int** | Segments now in the media. |  |
| **sentences** | **kotlin.Int** | Sentences now in the media. |  |
| **tokens** | **kotlin.Int** | Word tokens. |  |
| **uniqueEntries** | **kotlin.Int** | Distinct entries (&#x3D; deck items). |  |
| **unmapped** | [**kotlin.collections.List&lt;JobResultMediaUnmappedInner&gt;**](JobResultMediaUnmappedInner.md) | jpdb deck import only - vocabulary that maps to no JMdict entry (empty otherwise). Nothing is dropped silently. |  |
| **unmappedTokens** | **kotlin.Int** | Tokens that matched no entry. |  |



