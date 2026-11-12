
# Cardcreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **id** | **kotlin.String** | Id of the new card. Equals the envelope&#39;s entity_id. |  |
| **kind** | **kotlin.String** | Card kind, a key of the note type&#39;s templates (not validated against them). Immutable. |  |
| **noteId** | **kotlin.String** | Parent note; must exist (may be tombstoned or merged). Immutable. |  |
| **buriedUntil** | **kotlin.Int** | Initial value of register &#39;buried_until&#39;. Default null. |  [optional] |
| **flags** | **kotlin.Int** | Initial value of register &#39;flags&#39;, 0-7. Default 0. |  [optional] |
| **presetId** | **kotlin.String** | Scheduling preset; must exist. Default: the Default preset 00000000-0000-7000-8000-000000000010. |  [optional] |
| **suspended** | **kotlin.Boolean** | Initial value of register &#39;suspended&#39;. Default false. |  [optional] |



