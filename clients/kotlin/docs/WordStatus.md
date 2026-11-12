
# WordStatus

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **autoStatus** | [**inline**](#AutoStatus) | Status derived from the entry&#39;s vocab cards; null &#x3D; no live vocab note. |  |
| **entrySeq** | **kotlin.Long** | JMdict sequence number (&#x60;ref.dict_entry.seq&#x60;). |  |
| **overrideOrigin** | [**inline**](#OverrideOrigin) | Who set the override. |  |
| **overrideStatus** | [**inline**](#OverrideStatus) | Explicit override; null &#x3D; none. |  |
| **status** | [**WordStatusValue**](WordStatusValue.md) | Effective status. |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change; null when there is no row. |  |


<a id="AutoStatus"></a>
## Enum: auto_status
| Name | Value |
| ---- | ----- |
| autoStatus | new, learning, known,  |


<a id="OverrideOrigin"></a>
## Enum: override_origin
| Name | Value |
| ---- | ----- |
| overrideOrigin | manual, jpdb_import,  |


<a id="OverrideStatus"></a>
## Enum: override_status
| Name | Value |
| ---- | ----- |
| overrideStatus | known, never_forget, blacklisted, ignored,  |



