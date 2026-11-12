
# NoteTypeUpdatedSchemaSet

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **css** | **kotlin.String** | Register &#39;css&#39;. |  [optional] |
| **fields** | [**kotlin.collections.List&lt;NoteTypeCreatedSchemaFieldsInner&gt;**](NoteTypeCreatedSchemaFieldsInner.md) | Ordered field definitions; the whole array is ONE register (&#39;fields&#39;). Keys must be unique within the array (server rejects duplicates with invalid_value). Keys are never renamed. For a vocab note type the keys MUST include expression, reading and meaning (invalid_value otherwise; OPS.md, C-2). |  [optional] |
| **name** | **kotlin.String** | Register &#39;name&#39;. |  [optional] |
| **templates** | [**kotlin.collections.Map&lt;kotlin.String, Template?&gt;**](Template.md) | One register &#39;templates.&lt;kind&gt;&#39; per key present. null removes the kind from the object (the register keeps its clock). |  [optional] |



