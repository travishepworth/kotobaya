
# Meta

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **apiVersion** | [**inline**](#ApiVersion) | API major version. |  |
| **coreVersion** | **kotlin.String** | Version of the shared core the server schedules with. |  |
| **dictRelease** | [**MetaDictRelease**](MetaDictRelease.md) |  |  |
| **highWaterSeq** | **kotlin.Long** | Highest &#x60;change.seq&#x60; committed (0 &#x3D; empty log); same meaning as &#x60;PushResponse.high_water_seq&#x60;. |  |
| **limits** | [**MetaLimits**](MetaLimits.md) |  |  |
| **logEpoch** | **kotlin.String** | Identity of the server&#39;s log (contracts/sync/SYNC.md 6.2); &#x60;high_water_seq&#x60; belongs to this epoch. |  |
| **serverDeviceId** | **kotlin.String** | Origin device of server-built ops; always &#x60;00000000-0000-7000-8000-000000000001&#x60;. |  |
| **serverTime** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Server clock. |  |
| **serverVersion** | **kotlin.String** | Server build version. |  |


<a id="ApiVersion"></a>
## Enum: api_version
| Name | Value |
| ---- | ----- |
| apiVersion | v1 |



