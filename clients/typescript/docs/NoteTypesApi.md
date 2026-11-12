# NoteTypesApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createNoteType**](NoteTypesApi.md#createnotetype) | **POST** /note-types | Create a note type. |
| [**deleteNoteType**](NoteTypesApi.md#deletenotetype) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types. |
| [**getNoteType**](NoteTypesApi.md#getnotetype) | **GET** /note-types/{note_type_id} | One note type. |
| [**listNoteTypes**](NoteTypesApi.md#listnotetypes) | **GET** /note-types | List note types (built-ins and your own). |
| [**updateNoteType**](NoteTypesApi.md#updatenotetype) | **PATCH** /note-types/{note_type_id} | Edit a note type\&#39;s name, fields, templates or CSS. |



## createNoteType

> NoteType createNoteType(noteTypeCreate)

Create a note type.

Server emits &#x60;note_type.created&#x60;. &#x60;base_type&#x60; is immutable. &#x60;fields&#x60; must be non-empty with unique keys; &#x60;templates&#x60; must contain at least one card kind. &#x60;builtin&#x60; is always &#x60;false&#x60; for created types. A &#x60;vocab&#x60; type MUST have field keys &#x60;expression&#x60;, &#x60;reading&#x60; and &#x60;meaning&#x60; (custom types such as a Takoboto one map their fields onto them); otherwise &#x60;422 validation_failed&#x60;, error &#x60;invalid_value&#x60; at &#x60;/fields&#x60;. The same holds for a &#x60;PATCH&#x60; that replaces a vocab type\&#39;s &#x60;fields&#x60; (C-2, OPS.md &#x60;note_type.created&#x60;). 

### Example

```ts
import {
  Configuration,
  NoteTypesApi,
} from '@kotobaya/api-client';
import type { CreateNoteTypeRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new NoteTypesApi(config);

  const body = {
    // NoteTypeCreate
    noteTypeCreate: {"base_type":"vocab","fields":[{"key":"expression","kind":"text","label":"Expression"},{"key":"reading","kind":"text","label":"Reading"},{"key":"meaning","kind":"text","label":"Meaning"}],"name":"Takoboto Vocab","templates":{"meaning":{"back":"{{meaning}}","default":true,"front":"<div class=\"expr\">{{expression}}</div>"},"reading":{"back":null,"default":true,"front":null}}},
  } satisfies CreateNoteTypeRequest;

  try {
    const data = await api.createNoteType(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **noteTypeCreate** | [NoteTypeCreate](NoteTypeCreate.md) |  | |

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | A note type with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
| **201** | Created. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## deleteNoteType

> deleteNoteType(noteTypeId)

Delete one of your own note types.

Server emits &#x60;note_type.deleted&#x60;. Built-in: &#x60;409 builtin_immutable&#x60;. Any live note uses it: &#x60;409 in_use&#x60; (delete or leave the notes first). Already deleted: &#x60;204&#x60;, no op.  Notes of a deleted type stay usable (SYNC.md 7.3): an offline device may still create a note of a type deleted elsewhere (its push is accepted), and deletion is permanent, so hiding such notes would lose them for good. They are listed, searched, edited (&#x60;PATCH /notes/{id}&#x60;) and their cards reviewed like any other; they are rendered with the type\&#39;s last &#x60;fields&#x60;, &#x60;templates&#x60; and &#x60;css&#x60;, which &#x60;GET /note-types/{id}?include_deleted&#x3D;true&#x60; returns. The deleted type is omitted from &#x60;GET /note-types&#x60; (without &#x60;include_deleted&#x60;) and &#x60;POST /notes&#x60; refuses it (a client SHOULD NOT offer it for new notes). 

### Example

```ts
import {
  Configuration,
  NoteTypesApi,
} from '@kotobaya/api-client';
import type { DeleteNoteTypeRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new NoteTypesApi(config);

  const body = {
    // string | Note type id.
    noteTypeId: 00000000-0000-7000-8000-000000000101,
  } satisfies DeleteNoteTypeRequest;

  try {
    const data = await api.deleteNoteType(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **noteTypeId** | `string` | Note type id. | [Defaults to `undefined`] |

### Return type

`void` (Empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **204** | Deleted. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getNoteType

> NoteType getNoteType(noteTypeId, includeDeleted)

One note type.

### Example

```ts
import {
  Configuration,
  NoteTypesApi,
} from '@kotobaya/api-client';
import type { GetNoteTypeRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new NoteTypesApi(config);

  const body = {
    // string | Note type id.
    noteTypeId: 00000000-0000-7000-8000-000000000101,
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies GetNoteTypeRequest;

  try {
    const data = await api.getNoteType(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **noteTypeId** | `string` | Note type id. | [Defaults to `undefined`] |
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The note type. |  -  |
| **404** | No such resource. |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listNoteTypes

> NoteTypeList listNoteTypes(includeDeleted)

List note types (built-ins and your own).

Not paginated. Ordered by &#x60;builtin&#x60; descending, then &#x60;created_at&#x60;, then &#x60;id&#x60;.

### Example

```ts
import {
  Configuration,
  NoteTypesApi,
} from '@kotobaya/api-client';
import type { ListNoteTypesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new NoteTypesApi(config);

  const body = {
    // boolean | Also return tombstoned rows (`deleted_at` set). (optional)
    includeDeleted: false,
  } satisfies ListNoteTypesRequest;

  try {
    const data = await api.listNoteTypes(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **includeDeleted** | `boolean` | Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [Optional] [Defaults to `false`] |

### Return type

[**NoteTypeList**](NoteTypeList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Note types. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateNoteType

> NoteType updateNoteType(noteTypeId, noteTypePatch)

Edit a note type\&#39;s name, fields, templates or CSS.

Server emits one &#x60;note_type.updated&#x60; with the registers present in the body: &#x60;name&#x60;, &#x60;fields&#x60; (whole array), &#x60;css&#x60;, and one &#x60;templates.&lt;kind&gt;&#x60; register per key of &#x60;templates&#x60;.  - &#x60;fields&#x60; replaces the whole array. Existing keys may be reordered and relabelled and new   keys added. Removing a key is allowed for your own types (note values under that key are   kept in &#x60;note.fields&#x60;) and is &#x60;409 builtin_immutable&#x60; for built-in types. - &#x60;templates&#x60; is a partial map: only the kinds present are written. A kind cannot be   removed once it exists (cards may reference it); set &#x60;default: false&#x60; to stop creating it. - Works on built-in types (name, templates and CSS of built-ins may be edited). 

### Example

```ts
import {
  Configuration,
  NoteTypesApi,
} from '@kotobaya/api-client';
import type { UpdateNoteTypeRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new NoteTypesApi(config);

  const body = {
    // string | Note type id.
    noteTypeId: 00000000-0000-7000-8000-000000000101,
    // NoteTypePatch
    noteTypePatch: {"css":".expr { font-size: 3rem; }"},
  } satisfies UpdateNoteTypeRequest;

  try {
    const data = await api.updateNoteType(body);
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters


| Name | Type | Description  | Notes |
|------------- | ------------- | ------------- | -------------|
| **noteTypeId** | `string` | Note type id. | [Defaults to `undefined`] |
| **noteTypePatch** | [NoteTypePatch](NoteTypePatch.md) |  | |

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated note type. |  * Kotobaya-Seq -  <br>  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

