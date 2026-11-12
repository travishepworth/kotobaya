
# Reviewloggedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **cardId** | **kotlin.String** | Card as it was on the reviewing device; must exist on the server (may be tombstoned or merged). Never rewritten. |  |
| **id** | **kotlin.String** | review_log.id (UUIDv7 from the reviewing client). Equals entity_id. Distinct from op_id. |  |
| **rating** | **kotlin.Int** | FSRS rating 1 Again, 2 Hard, 3 Good, 4 Easy. null iff review_type &#x3D; manual. |  |
| **reviewType** | [**inline**](#ReviewType) | scheduled, learning, manual feed the schedule (replay set); drill, cram are logged only. |  |
| **reviewedAt** | **kotlin.Int** | Wall-clock instant of the answer on the reviewing device. |  |
| **utcOffsetMin** | **kotlin.Int** | Device UTC offset in minutes at review time (540 &#x3D; JST); null &#x3D; unknown. Native clients MUST send a number. |  |
| **button** | [**inline**](#Button) | Button pressed; must agree with rating (again/nothing/something&#x3D;1, hard&#x3D;2, good/okay&#x3D;3, easy&#x3D;4). null &#x3D; unknown, and always null for manual. Default null. |  [optional] |
| **durationMs** | **kotlin.Int** | Time from card shown to answer, capped at 600000; null &#x3D; unknown. Default null. |  [optional] |
| **importSource** | [**inline**](#ImportSource) | Server-emitted import ops only: &#39;jpdb&#39;. Clients MUST omit it or send null. |  [optional] |
| **manualAction** | [**inline**](#ManualAction) | Required (non-null) iff review_type &#x3D; manual. set_due: keep memory state, due :&#x3D; manual_due_at. reset: card returns to state new. |  [optional] |
| **manualDueAt** | **kotlin.Int** | Non-null iff manual_action &#x3D; set_due. |  [optional] |
| **replayHlc** | **kotlin.String** | Server-emitted import ops only: the value stored in review_log.hlc (replay position), synthesised as &lt;historical ms&gt;-&lt;running counter&gt;-&lt;server device id&gt;. When absent/null, review_log.hlc &#x3D; the op&#39;s hlc. Requires import_source. |  [optional] |
| **sentenceId** | **kotlin.String** | Sentence shown for rotating-example cards (soft reference). Default null. |  [optional] |


<a id="ReviewType"></a>
## Enum: review_type
| Name | Value |
| ---- | ----- |
| reviewType | scheduled, learning, drill, cram, manual |


<a id="Button"></a>
## Enum: button
| Name | Value |
| ---- | ----- |
| button | again, hard, good, easy, nothing, something, okay |


<a id="ImportSource"></a>
## Enum: import_source
| Name | Value |
| ---- | ----- |
| importSource | jpdb |


<a id="ManualAction"></a>
## Enum: manual_action
| Name | Value |
| ---- | ----- |
| manualAction | set_due, reset |



