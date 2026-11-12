
# NoteTypecreatedschema

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **baseType** | [**inline**](#BaseType) | Which of the six note types this is. Immutable. |  |
| **fields** | [**kotlin.collections.List&lt;NoteTypeCreatedSchemaFieldsInner&gt;**](NoteTypeCreatedSchemaFieldsInner.md) | Ordered field definitions; the whole array is ONE register (&#39;fields&#39;). Keys must be unique within the array (server rejects duplicates with invalid_value). Keys are never renamed. For base_type vocab the keys MUST include expression, reading and meaning (server rejects otherwise with invalid_value; OPS.md, C-2). |  |
| **id** | **kotlin.String** | Id of the new note type. Equals the envelope&#39;s entity_id. |  |
| **name** | **kotlin.String** | Display name. |  |
| **templates** | [**kotlin.collections.Map&lt;kotlin.String, Template&gt;**](Template.md) | Card kinds: {kind: template}. Each kind is its own register &#39;templates.&lt;kind&gt;&#39;. |  |
| **css** | **kotlin.String** | Extra CSS appended after assets/card/ CSS. Default &#39;&#39;. |  [optional] |


<a id="BaseType"></a>
## Enum: base_type
| Name | Value |
| ---- | ----- |
| baseType | vocab, kanji, phrase, grammar, sentence, custom |



