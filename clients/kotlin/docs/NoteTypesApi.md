# NoteTypesApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createNoteType**](NoteTypesApi.md#createNoteType) | **POST** /note-types | Create a note type. |
| [**deleteNoteType**](NoteTypesApi.md#deleteNoteType) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types. |
| [**getNoteType**](NoteTypesApi.md#getNoteType) | **GET** /note-types/{note_type_id} | One note type. |
| [**listNoteTypes**](NoteTypesApi.md#listNoteTypes) | **GET** /note-types | List note types (built-ins and your own). |
| [**updateNoteType**](NoteTypesApi.md#updateNoteType) | **PATCH** /note-types/{note_type_id} | Edit a note type&#39;s name, fields, templates or CSS. |


<a id="createNoteType"></a>
# **createNoteType**
> NoteType createNoteType(noteTypeCreate)

Create a note type.

Server emits &#x60;note_type.created&#x60;. &#x60;base_type&#x60; is immutable. &#x60;fields&#x60; must be non-empty with unique keys; &#x60;templates&#x60; must contain at least one card kind. &#x60;builtin&#x60; is always &#x60;false&#x60; for created types. A &#x60;vocab&#x60; type MUST have field keys &#x60;expression&#x60;, &#x60;reading&#x60; and &#x60;meaning&#x60; (custom types such as a Takoboto one map their fields onto them); otherwise &#x60;422 validation_failed&#x60;, error &#x60;invalid_value&#x60; at &#x60;/fields&#x60;. The same holds for a &#x60;PATCH&#x60; that replaces a vocab type&#39;s &#x60;fields&#x60; (C-2, OPS.md &#x60;note_type.created&#x60;). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NoteTypesApi()
val noteTypeCreate : NoteTypeCreate = {"base_type":"vocab","fields":[{"key":"expression","kind":"text","label":"Expression"},{"key":"reading","kind":"text","label":"Reading"},{"key":"meaning","kind":"text","label":"Meaning"}],"name":"Takoboto Vocab","templates":{"meaning":{"back":"{{meaning}}","default":true,"front":"<div class=\"expr\">{{expression}}</div>"},"reading":{"back":null,"default":true,"front":null}}} // NoteTypeCreate | 
try {
    val result : NoteType = apiInstance.createNoteType(noteTypeCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NoteTypesApi#createNoteType")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NoteTypesApi#createNoteType")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteTypeCreate** | [**NoteTypeCreate**](NoteTypeCreate.md)|  | |

### Return type

[**NoteType**](NoteType.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

<a id="deleteNoteType"></a>
# **deleteNoteType**
> deleteNoteType(noteTypeId)

Delete one of your own note types.

Server emits &#x60;note_type.deleted&#x60;. Built-in: &#x60;409 builtin_immutable&#x60;. Any live note uses it: &#x60;409 in_use&#x60; (delete or leave the notes first). Already deleted: &#x60;204&#x60;, no op.  Notes of a deleted type stay usable (SYNC.md 7.3): an offline device may still create a note of a type deleted elsewhere (its push is accepted), and deletion is permanent, so hiding such notes would lose them for good. They are listed, searched, edited (&#x60;PATCH /notes/{id}&#x60;) and their cards reviewed like any other; they are rendered with the type&#39;s last &#x60;fields&#x60;, &#x60;templates&#x60; and &#x60;css&#x60;, which &#x60;GET /note-types/{id}?include_deleted&#x3D;true&#x60; returns. The deleted type is omitted from &#x60;GET /note-types&#x60; (without &#x60;include_deleted&#x60;) and &#x60;POST /notes&#x60; refuses it (a client SHOULD NOT offer it for new notes). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NoteTypesApi()
val noteTypeId : kotlin.String = 00000000-0000-7000-8000-000000000101 // kotlin.String | Note type id.
try {
    apiInstance.deleteNoteType(noteTypeId)
} catch (e: ClientException) {
    println("4xx response calling NoteTypesApi#deleteNoteType")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NoteTypesApi#deleteNoteType")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteTypeId** | **kotlin.String**| Note type id. | |

### Return type

null (empty response body)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

<a id="getNoteType"></a>
# **getNoteType**
> NoteType getNoteType(noteTypeId, includeDeleted)

One note type.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NoteTypesApi()
val noteTypeId : kotlin.String = 00000000-0000-7000-8000-000000000101 // kotlin.String | Note type id.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : NoteType = apiInstance.getNoteType(noteTypeId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NoteTypesApi#getNoteType")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NoteTypesApi#getNoteType")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteTypeId** | **kotlin.String**| Note type id. | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**NoteType**](NoteType.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="listNoteTypes"></a>
# **listNoteTypes**
> NoteTypeList listNoteTypes(includeDeleted)

List note types (built-ins and your own).

Not paginated. Ordered by &#x60;builtin&#x60; descending, then &#x60;created_at&#x60;, then &#x60;id&#x60;.

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NoteTypesApi()
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : NoteTypeList = apiInstance.listNoteTypes(includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NoteTypesApi#listNoteTypes")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NoteTypesApi#listNoteTypes")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**NoteTypeList**](NoteTypeList.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

<a id="updateNoteType"></a>
# **updateNoteType**
> NoteType updateNoteType(noteTypeId, noteTypePatch)

Edit a note type&#39;s name, fields, templates or CSS.

Server emits one &#x60;note_type.updated&#x60; with the registers present in the body: &#x60;name&#x60;, &#x60;fields&#x60; (whole array), &#x60;css&#x60;, and one &#x60;templates.&lt;kind&gt;&#x60; register per key of &#x60;templates&#x60;.  - &#x60;fields&#x60; replaces the whole array. Existing keys may be reordered and relabelled and new   keys added. Removing a key is allowed for your own types (note values under that key are   kept in &#x60;note.fields&#x60;) and is &#x60;409 builtin_immutable&#x60; for built-in types. - &#x60;templates&#x60; is a partial map: only the kinds present are written. A kind cannot be   removed once it exists (cards may reference it); set &#x60;default: false&#x60; to stop creating it. - Works on built-in types (name, templates and CSS of built-ins may be edited). 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NoteTypesApi()
val noteTypeId : kotlin.String = 00000000-0000-7000-8000-000000000101 // kotlin.String | Note type id.
val noteTypePatch : NoteTypePatch = {"css":".expr { font-size: 3rem; }"} // NoteTypePatch | 
try {
    val result : NoteType = apiInstance.updateNoteType(noteTypeId, noteTypePatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NoteTypesApi#updateNoteType")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NoteTypesApi#updateNoteType")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteTypeId** | **kotlin.String**| Note type id. | |
| **noteTypePatch** | [**NoteTypePatch**](NoteTypePatch.md)|  | |

### Return type

[**NoteType**](NoteType.md)

### Authorization


Configure sessionCookie:
    ApiClient.apiKey["kotobaya_session"] = ""
    ApiClient.apiKeyPrefix["kotobaya_session"] = ""
Configure bearerAuth statically:
```kotlin
ApiClient.accessToken = ""
```

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

