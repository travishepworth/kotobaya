
# Opschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **deviceId** | **kotlin.String** | Origin device &#x3D; the device that minted hlc. Must equal the last 36 characters of hlc. |  |
| **entityId** | **kotlin.String** | Primary key of the targeted row as text, derived from the payload as listed in ops/index.json (parts joined with &#39;|&#39;). |  |
| **entityType** | **kotlin.String** | Table the op targets, fixed per op_type (ops/index.json). |  |
| **hlc** | **kotlin.String** | Clock of the op. Decides every last-writer-wins comparison and supplies the op&#39;s wall time (first 12 hex digits). |  |
| **opId** | **kotlin.String** | UUIDv7 minted with the op. The idempotency key: the server applies an op_id at most once. |  |
| **opType** | [**inline**](#OpType) | Dotted op name. The closed set of v1 op types. |  |
| **opVersion** | [**kotlin.Any**](.md) | Version of the payload schema for this op_type. Always 1 in this contract. |  |
| **payload** | [**kotlin.Any**](.md) | Op-specific body; schema chosen by op_type. |  |
| **causedByOpId** | **kotlin.String** | Pulled ops only: op_id of the op that made the server emit this one (merges, cascades), else null. |  [optional] |
| **seq** | **kotlin.Int** | Server sequence number (change.seq). Present on every pulled op; MUST be absent on pushed ops. |  [optional] |


<a id="OpType"></a>
## Enum: op_type
| Name | Value |
| ---- | ----- |
| opType | note_type.created, note_type.updated, note_type.deleted, note.created, note.updated, note.deleted, note.merged, card.created, card.suspended, card.unsuspended, card.unburied, card.deleted, card.buried, card.flag_set, card.preset_set, card.merged, review.logged, review.undone, word.status_set, word.status_cleared, deck.created, deck.updated, deck.deleted, deck.content_published, deck.item_put, deck.item_removed, confusable.suggested, confusable.confirmed, confusable.rejected, grammar.created, grammar.updated, grammar.deleted, grammar.span_put, grammar.span_removed, settings.set, fsrs_params.created, fsrs_params.updated, fsrs_params.deleted, fsrs_params.published, media.created, media.updated, media.deleted, media.content_published, parse_override.put, parse_override.deleted |



