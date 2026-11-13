
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
| **unmapped** | [**kotlin.collections.List&lt;JobResultMediaUnmappedInner&gt;**](JobResultMediaUnmappedInner.md) | jpdb deck import only - vocabulary that maps to no JMdict entry and has no stored correction (&#x60;listJpdbVocab&#x60; status &#x60;unresolved&#x60;), by &#x60;occurrence_count&#x60; descending then &#x60;jpdb_vid&#x60;; empty otherwise. Items the user marked &#x60;ignored&#x60; are not listed but their tokens still count in &#x60;unmapped_tokens&#x60;. Nothing is dropped silently (C-11). |  |
| **unmappedTokens** | **kotlin.Int** | Tokens that matched no entry. |  |



