
# JobResultGrammarDetectFanOut

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **enqueued** | **kotlin.Int** | Per-media jobs actually inserted (&#x60;&lt;&#x3D; media&#x60;); the rest collapsed into an identical queued or running job (same dedupe key). |  |
| **media** | **kotlin.Int** | Eligible media (live, &#x60;content_version &gt; 0&#x60;, &#x60;source&#x60; not &#x60;jpdb&#x60;). |  |
| **rulesDigest** | **kotlin.String** | Opaque worker-defined digest of the live points&#39; detection rules read by this job; compare for equality only. Part of the per-media dedupe keys. |  |



