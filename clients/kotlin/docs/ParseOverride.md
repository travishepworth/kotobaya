
# ParseOverride

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone (permanent). |  |
| **id** | **kotlin.String** | Override id. |  |
| **matchText** | **kotlin.String** | Exact surface substring that is re-tokenised. |  |
| **mediaId** | **kotlin.String** | Scope - null &#x3D; all media. |  |
| **note** | **kotlin.String** | Free-form remark. |  |
| **sentenceId** | **kotlin.String** | Narrowest scope - only this sentence. |  |
| **tokens** | [**kotlin.collections.List&lt;OverrideToken&gt;**](OverrideToken.md) | Replacement tokens; surfaces concatenate to &#x60;match_text&#x60;. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |



