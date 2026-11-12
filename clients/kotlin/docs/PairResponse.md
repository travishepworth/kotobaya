
# PairResponse

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **device** | [**Device**](Device.md) |  |  |
| **highWaterSeq** | **kotlin.Long** | Current sync head; a new device pulls from 0 up to at least this. |  |
| **scopes** | [**kotlin.collections.List&lt;Scope&gt;**](Scope.md) | Always &#x60;[read, write, review]&#x60;. |  |
| **serverDeviceId** | **kotlin.String** | The server device id. |  |
| **token** | **kotlin.String** | The device token. Shown only here. |  |
| **tokenId** | **kotlin.String** | &#x60;api_token.id&#x60; of the new token. |  |



