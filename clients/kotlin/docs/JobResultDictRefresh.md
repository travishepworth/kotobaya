
# JobResultDictRefresh

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **propertyEntries** | **kotlin.Int** | Live entries in the current release. |  |
| **releaseId** | **kotlin.Int** | New release id; null when nothing changed and &#x60;force&#x60; was false. |  |
| **retired** | **kotlin.Int** | Entries retired by this release. |  |
| **version** | **kotlin.String** | New release version. |  |
| **vocabLists** | [**kotlin.collections.List&lt;VocabListReport&gt;**](VocabListReport.md) | C-21: per loaded vocabulary list of the new release, ordered by family then ordinal (&#x60;jlpt-n5&#x60; first): how its source rows were accounted for (&#x60;source_rows &#x3D; entries + duplicates + shadowed + unmatched&#x60;; &#x60;contracts/db/VOCAB_LISTS.md&#x60; 3.3). Empty when no list is configured; absent when no release was built. |  [optional] |



