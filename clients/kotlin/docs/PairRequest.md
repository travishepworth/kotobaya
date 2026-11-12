
# PairRequest

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **code** | **kotlin.String** | The pairing code. |  |
| **deviceId** | **kotlin.String** | UUIDv7 that is the node id of the device&#39;s HLC. Generated at first launch and on every loss or restore of the local database (reinstall, cleared data, restored backup); reused only by the same intact install when re-pairing (contracts/sync/SYNC.md 3.3). |  |
| **name** | **kotlin.String** | Display name. |  |
| **hlcLast** | **kotlin.String** | The device&#39;s persisted &#x60;sync_state.hlc_last&#x60; (SYNC.md 3.2), sent when re-pairing an existing &#x60;device_id&#x60;. Its device part must equal &#x60;device_id&#x60;. If it is below the greatest HLC the server has logged for that device, pairing fails with &#x60;422&#x60; (&#x60;hlc_regressed&#x60;) and the client must pair as a new device. Omitted by a fresh install. |  [optional] |
| **kind** | [**inline**](#Kind) | Device kind. |  [optional] |
| **pushTopic** | [**java.net.URI**](java.net.URI.md) | UnifiedPush endpoint, if already known. |  [optional] |


<a id="Kind"></a>
## Enum: kind
| Name | Value |
| ---- | ----- |
| kind | android, script |



