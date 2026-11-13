
# JobResultGrammarDetect

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **changed** | **kotlin.Boolean** | True iff this job published a new content version. |  |
| **mediaId** | **kotlin.String** | The media. |  |
| **enqueued** | **kotlin.Int** | Per-media jobs actually inserted (&#x60;&lt;&#x3D; media&#x60;); the rest collapsed into an identical queued or running job (same dedupe key). |  |
| **media** | **kotlin.Int** | Eligible media (live, &#x60;content_version &gt; 0&#x60;, &#x60;source&#x60; not &#x60;jpdb&#x60;). |  |
| **rulesDigest** | **kotlin.String** | Opaque worker-defined digest of the live points&#39; detection rules read by this job; compare for equality only. Part of the per-media dedupe keys. |  |
| **contentVersion** | **kotlin.Int** | Published version (&#x60;changed&#x60;), else the media&#39;s current version. Absent iff &#x60;skipped&#x60;. |  [optional] |
| **invalidRules** | **kotlin.Int** | Non-draft rules of live grammar points that failed to compile and were ignored (each is logged with its grammar point id and rule index). |  [optional] |
| **skipped** | [**inline**](#Skipped) | Why nothing was detected - no such media; media tombstoned; &#x60;source &#x3D; jpdb&#x60; (no sentences); &#x60;content_version &#x3D; 0&#x60; or no segments. Present iff skipped. |  [optional] |
| **spans** | **kotlin.Int** | Detected spans the media carries after this job (manual spans not counted). |  [optional] |


<a id="Skipped"></a>
## Enum: skipped
| Name | Value |
| ---- | ----- |
| skipped | unknown_media, media_deleted, not_applicable, not_parsed |



