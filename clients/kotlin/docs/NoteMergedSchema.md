
# Notemergedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **loserId** | **kotlin.String** | The note that is tombstoned and gets merged_into_id &#x3D; survivor_id. Equals entity_id. |  |
| **survivorId** | **kotlin.String** | The note that already held the reference. Never itself merged at the time the op is emitted. Differs from loser_id. |  |



