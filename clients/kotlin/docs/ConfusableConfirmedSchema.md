
# Confusableconfirmedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **noteAId** | **kotlin.String** | The smaller note id of the pair (lowercase text order). Must exist. |  |
| **noteBId** | **kotlin.String** | The larger note id. note_a_id &lt; note_b_id. Must exist. |  |
| **reason** | [**inline**](#Reason) | Why they are confused. entity_id &#x3D; &#39;&lt;note_a_id&gt;|&lt;note_b_id&gt;|&lt;reason&gt;&#39;. |  |


<a id="Reason"></a>
## Enum: reason
| Name | Value |
| ---- | ----- |
| reason | homophone, look_alike, meaning |



