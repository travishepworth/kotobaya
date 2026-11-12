
# WordstatusSetschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **entrySeq** | **kotlin.Int** | Entry. entity_id is its decimal text. |  |
| **status** | [**inline**](#Status) | The override. |  |
| **origin** | [**inline**](#Origin) | Who set it. Default &#39;manual&#39;. &#39;jpdb_import&#39; is only used by the server&#39;s import job. |  [optional] |


<a id="Status"></a>
## Enum: status
| Name | Value |
| ---- | ----- |
| status | known, never_forget, blacklisted, ignored |


<a id="Origin"></a>
## Enum: origin
| Name | Value |
| ---- | ----- |
| origin | manual, jpdb_import |



