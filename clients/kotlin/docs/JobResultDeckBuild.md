
# JobResultDeckBuild

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **contentVersion** | **kotlin.Int** | Content version published, or the deck&#39;s current version when &#x60;changed&#x60; is false. |  |
| **deckId** | **kotlin.String** | The deck. |  |
| **items** | **kotlin.Int** | Items in the snapshot (at least 1 for a C-21 build; an empty list fails the job instead). |  |
| **changed** | **kotlin.Boolean** | false &#x3D; the computed items equal the installed ones; nothing was published and no op was emitted. |  [optional] |
| **listKey** | **kotlin.String** | The deck&#39;s list key. |  [optional] |
| **unmatched** | **kotlin.Int** | Source rows of the vocabulary list that matched no dictionary entry (&#x60;ref.vocab_list.unmatched_rows&#x60;; the rows are in &#x60;ref.vocab_list_unmatched&#x60;). 0 for frequency bands. |  [optional] |



