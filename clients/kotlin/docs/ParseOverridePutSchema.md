
# ParseOverrideputschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the parse override. Equals the envelope&#39;s entity_id. |  |
| **matchText** | **kotlin.String** | Exact surface substring this override re-tokenises. At most 200 code points (C-6): the column has a btree index (parse_override_match_idx), whose entries Postgres caps at about 2.7 kB. |  |
| **tokens** | [**kotlin.collections.List&lt;ParseOverridePutSchemaTokensInner&gt;**](ParseOverridePutSchemaTokensInner.md) | Replacement tokens; surfaces must concatenate to match_text (server rejects otherwise). |  |
| **mediaId** | **kotlin.String** | Scope: null &#x3D; all media. Soft reference. Default null. |  [optional] |
| **note** | **kotlin.String** | Free-text remark. Default &#39;&#39;. |  [optional] |
| **sentenceId** | **kotlin.String** | Narrowest scope: one sentence; requires media_id. Soft reference. Default null. |  [optional] |



