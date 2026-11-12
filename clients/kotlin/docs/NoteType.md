
# NoteType

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **baseType** | [**NoteBaseType**](NoteBaseType.md) |  |  |
| **builtin** | **kotlin.Boolean** | Built-ins cannot be deleted and keep their field keys. |  |
| **createdAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Creation time (epoch for built-ins). |  |
| **css** | **kotlin.String** | Extra CSS appended after the shared card CSS. |  |
| **deletedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Tombstone. |  |
| **fields** | [**kotlin.collections.List&lt;NoteField&gt;**](NoteField.md) | Ordered field definitions. |  |
| **id** | **kotlin.String** | Note type id. Built-ins are &#x60;00000000-0000-7000-8000-000000000101&#x60; to &#x60;...106&#x60;. |  |
| **name** | **kotlin.String** | Display name. |  |
| **templates** | [**kotlin.collections.Map&lt;kotlin.String, CardTemplate&gt;**](CardTemplate.md) | Card kinds, keyed by kind (&#x60;^[a-z][a-z0-9_]*$&#x60;). |  |
| **updatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Last change. |  |



