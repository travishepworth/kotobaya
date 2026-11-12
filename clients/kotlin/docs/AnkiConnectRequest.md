
# AnkiConnectRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **action** | **kotlin.String** | Supported: &#x60;version&#x60;, &#x60;deckNames&#x60;, &#x60;modelNames&#x60;, &#x60;modelFieldNames&#x60;, &#x60;addNote&#x60;, &#x60;canAddNotes&#x60;, &#x60;findNotes&#x60;, &#x60;storeMediaFile&#x60;. Any other value yields error &#x60;unsupported action&#x60;. |  |
| **key** | **kotlin.String** | A personal access token with the &#x60;write&#x60; scope, when no other credential is sent. |  [optional] |
| **params** | [**kotlin.collections.Map&lt;kotlin.String, kotlin.Any&gt;**](kotlin.Any.md) | Action parameters (table on &#x60;POST /ankiconnect&#x60;). |  [optional] |
| **version** | **kotlin.Int** | Protocol version the client speaks; the server always answers in the version 6 shape. |  [optional] |



