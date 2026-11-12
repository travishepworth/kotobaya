
# NotePatch

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **addTags** | **kotlin.collections.List&lt;kotlin.String&gt;** | Tags to add to the current set. If the merged set would exceed 200 tags the request is &#x60;422 validation_failed&#x60;, error code &#x60;out_of_range&#x60; at &#x60;/add_tags&#x60; (C-2). |  [optional] |
| **entrySeq** | **kotlin.Long** | Re-match to another entry, or null to un-match (vocab notes only). |  [optional] |
| **fields** | **kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt;** | Field values to set (partial map); &#x60;\&quot;\&quot;&#x60; clears a field. |  [optional] |
| **mnemonic** | **kotlin.String** | Mnemonic (Markdown). |  [optional] |
| **removeTags** | **kotlin.collections.List&lt;kotlin.String&gt;** | Tags to remove from the current set; absent ones are ignored. |  [optional] |
| **sourceMediaId** | **kotlin.String** | Soft reference to the media. |  [optional] |
| **sourceSentence** | **kotlin.String** | Source sentence. |  [optional] |
| **sourceSentenceId** | **kotlin.String** | Soft reference to the sentence. |  [optional] |
| **tags** | **kotlin.collections.List&lt;kotlin.String&gt;** | The whole new tag set. |  [optional] |



