
# ClientErrorReport

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **items** | [**kotlin.collections.List&lt;ClientErrorItem&gt;**](ClientErrorItem.md) | The reports, in any order. More than 20 is &#x60;413 payload_too_large&#x60; (split the batch); none is &#x60;422&#x60;. |  |
| **dropped** | **kotlin.Int** | Optional, default 0. Number of reports this client discarded (queue overflow, or a batch refused with a non-retryable status) since its last &#x60;202&#x60;. Logged as one WARN line when above 0.  |  [optional] |



