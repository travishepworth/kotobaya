# NotesApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createNote**](NotesApi.md#createNote) | **POST** /notes | Create a note and its cards. |
| [**deleteNote**](NotesApi.md#deleteNote) | **DELETE** /notes/{note_id} | Delete a note (permanent tombstone; hides all its cards). |
| [**getNote**](NotesApi.md#getNote) | **GET** /notes/{note_id} | One note with its live cards. |
| [**listNotes**](NotesApi.md#listNotes) | **GET** /notes | List or search notes. |
| [**updateNote**](NotesApi.md#updateNote) | **PATCH** /notes/{note_id} | Edit a note&#39;s fields, tags, mnemonic, source sentence or dictionary match. |


<a id="createNote"></a>
# **createNote**
> Note createNote(noteCreate, onDuplicate)

Create a note and its cards.

Server emits, in one transaction and in this order: &#x60;note.created&#x60;; one &#x60;card.created&#x60; per card kind (server-generated card ids; kinds &#x3D; &#x60;card_kinds&#x60; if given, else the note type&#39;s kinds with &#x60;default: true&#x60;, in ascending kind order); &#x60;deck.item_put&#x60; if &#x60;deck_id&#x60; is given (item key &#x60;e:&lt;entry_seq&gt;&#x60; when the note has an &#x60;entry_seq&#x60;, else &#x60;n:&lt;note id&gt;&#x60;, position &#x3D; current maximum position in the deck + 1024).  Rules: - &#x60;note_type_id&#x60; must be a live note type. &#x60;type&#x60; is derived from it. - At most one of &#x60;entry_seq&#x60;, &#x60;kanji_literal&#x60;, &#x60;grammar_point_id&#x60;, and only the one that   fits the base type (&#x60;vocab&#x60;, &#x60;kanji&#x60;, &#x60;grammar&#x60; respectively); otherwise &#x60;422&#x60;.   &#x60;entry_seq&#x60; must exist in &#x60;ref.dict_entry&#x60; (retired entries are accepted);   &#x60;kanji_literal&#x60; need not exist in &#x60;ref.kanji&#x60;; &#x60;grammar_point_id&#x60; must be a live   grammar point. - Unknown keys in &#x60;fields&#x60; are stored as given. Missing keys mean &#x60;\&quot;\&quot;&#x60;. - &#x60;tags&#x60;: each 1 to 64 characters, no whitespace, not &#x60;leech&#x60;; stored de-duplicated and   sorted ascending. - &#x60;card_kinds&#x60; entries must be keys of the note type&#39;s &#x60;templates&#x60;; an empty array   creates a note without cards. - &#x60;deck_id&#x60; must be a live deck of type &#x60;user&#x60;; otherwise &#x60;409 deck_read_only&#x60;.  **Duplicate reference.** If a live note already references the same entry, kanji or grammar point, nothing is created: with &#x60;on_duplicate&#x3D;error&#x60; (default) the response is &#x60;409 already_exists&#x60; with &#x60;existing_id&#x60;; with &#x60;on_duplicate&#x3D;return_existing&#x60; it is &#x60;200&#x60; with the existing note, unchanged (if &#x60;deck_id&#x60; was given the &#x60;deck.item_put&#x60; is still emitted). REST never creates a duplicate on purpose; duplicates created offline are merged by the op-apply path (SCHEMA.md 4.2).  **Idempotency.** An &#x60;id&#x60; that already exists (live, deleted or merged) returns that note (or its survivor) with &#x60;200&#x60; and emits nothing. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NotesApi()
val noteCreate : NoteCreate = {"card_kinds":["basic"],"fields":{"back":"four-character idiom","front":"四字熟語"},"note_type_id":"00000000-0000-7000-8000-000000000106"} // NoteCreate | 
val onDuplicate : kotlin.String = return_existing // kotlin.String | What to do when a live note already references the same entry, kanji or grammar point.
try {
    val result : Note = apiInstance.createNote(noteCreate, onDuplicate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NotesApi#createNote")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NotesApi#createNote")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteCreate** | [**NoteCreate**](NoteCreate.md)|  | |
| **onDuplicate** | **kotlin.String**| What to do when a live note already references the same entry, kanji or grammar point. | [optional] [enum: error, return_existing] |

### Return type

[**Note**](Note.md)

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

<a id="deleteNote"></a>
# **deleteNote**
> deleteNote(noteId)

Delete a note (permanent tombstone; hides all its cards).

Server emits &#x60;note.deleted&#x60;. Cards are hidden through the note&#39;s tombstone; no &#x60;card.deleted&#x60; ops are emitted. Reviews stay in the log. Word status &#x60;auto_status&#x60; is re-derived. Already deleted, or the id of a merge loser (deleting a duplicate never deletes the survivor): &#x60;204&#x60;, no op. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NotesApi()
val noteId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a01 // kotlin.String | Note id.
try {
    apiInstance.deleteNote(noteId)
} catch (e: ClientException) {
    println("4xx response calling NotesApi#deleteNote")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NotesApi#deleteNote")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteId** | **kotlin.String**| Note id. | |

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

<a id="getNote"></a>
# **getNote**
> Note getNote(noteId, includeDeleted)

One note with its live cards.

If the id belongs to a merge loser the survivor is returned (status &#x60;200&#x60;, &#x60;Content-Location: /api/v1/notes/&lt;survivor id&gt;&#x60;), with or without &#x60;include_deleted&#x60;. A deleted, unmerged note is &#x60;410 gone&#x60; unless &#x60;include_deleted&#x3D;true&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NotesApi()
val noteId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a01 // kotlin.String | Note id.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
try {
    val result : Note = apiInstance.getNote(noteId, includeDeleted)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NotesApi#getNote")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NotesApi#getNote")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteId** | **kotlin.String**| Note id. | |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |

### Return type

[**Note**](Note.md)

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

<a id="listNotes"></a>
# **listNotes**
> NotePage listNotes(q, type, noteTypeId, tag, entrySeq, kanjiLiteral, grammarPointId, updatedSince, includeDeleted, limit, cursor)

List or search notes.

All filters combine with AND. Ordered by &#x60;created_at&#x60; descending, then &#x60;id&#x60; descending. Merge losers appear only with &#x60;include_deleted&#x3D;true&#x60;, as tombstones with &#x60;merged_into_id&#x60; set. Each note embeds its live cards. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NotesApi()
val q : kotlin.String = 食べ // kotlin.String | Case-insensitive substring of any field value, the mnemonic or the source sentence (`note.search_text`).
val type : NoteBaseType = vocab // NoteBaseType | Only notes of this base type.
val noteTypeId : kotlin.String = 00000000-0000-7000-8000-000000000101 // kotlin.String | Only notes of this note type.
val tag : kotlin.String = anime // kotlin.String | Only notes carrying this exact tag. The virtual tag `leech` is not accepted here (use `GET /cards?query=tag:leech`).
val entrySeq : kotlin.Long = 1358280 // kotlin.Long | Only the (at most one live) note referencing this entry.
val kanjiLiteral : kotlin.String = 績 // kotlin.String | Only the kanji note for this kanji.
val grammarPointId : kotlin.String = 01931f40-4000-7000-8000-000000000901 // kotlin.String | Only the grammar note for this grammar point.
val updatedSince : java.time.OffsetDateTime = 2026-11-01T00:00:00.000Z // java.time.OffsetDateTime | Only notes with `updated_at` strictly after this instant.
val includeDeleted : kotlin.Boolean = false // kotlin.Boolean | Also return tombstoned rows (`deleted_at` set).
val limit : kotlin.Int = 50 // kotlin.Int | Page size.
val cursor : kotlin.String = eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19 // kotlin.String | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`.
try {
    val result : NotePage = apiInstance.listNotes(q, type, noteTypeId, tag, entrySeq, kanjiLiteral, grammarPointId, updatedSince, includeDeleted, limit, cursor)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NotesApi#listNotes")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NotesApi#listNotes")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **q** | **kotlin.String**| Case-insensitive substring of any field value, the mnemonic or the source sentence (&#x60;note.search_text&#x60;). | [optional] |
| **type** | [**NoteBaseType**](.md)| Only notes of this base type. | [optional] [enum: vocab, kanji, phrase, grammar, sentence, custom] |
| **noteTypeId** | **kotlin.String**| Only notes of this note type. | [optional] |
| **tag** | **kotlin.String**| Only notes carrying this exact tag. The virtual tag &#x60;leech&#x60; is not accepted here (use &#x60;GET /cards?query&#x3D;tag:leech&#x60;). | [optional] |
| **entrySeq** | **kotlin.Long**| Only the (at most one live) note referencing this entry. | [optional] |
| **kanjiLiteral** | **kotlin.String**| Only the kanji note for this kanji. | [optional] |
| **grammarPointId** | **kotlin.String**| Only the grammar note for this grammar point. | [optional] |
| **updatedSince** | **java.time.OffsetDateTime**| Only notes with &#x60;updated_at&#x60; strictly after this instant. | [optional] |
| **includeDeleted** | **kotlin.Boolean**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to false] |
| **limit** | **kotlin.Int**| Page size. | [optional] [default to 50] |
| **cursor** | **kotlin.String**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] |

### Return type

[**NotePage**](NotePage.md)

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

<a id="updateNote"></a>
# **updateNote**
> Note updateNote(noteId, notePatch)

Edit a note&#39;s fields, tags, mnemonic, source sentence or dictionary match.

Server emits one &#x60;note.updated&#x60; carrying exactly the registers the body touches:  | Body property | Register(s) written | |---|---| | &#x60;fields.&lt;key&gt;&#x60; | &#x60;fields.&lt;key&gt;&#x60; for each key present (&#x60;\&quot;\&quot;&#x60; clears the field) | | &#x60;tags&#x60; | &#x60;tags&#x60; (whole set) | | &#x60;add_tags&#x60; / &#x60;remove_tags&#x60; | &#x60;tags&#x60;: current set plus/minus the given tags, computed by the server at apply time | | &#x60;mnemonic&#x60; | &#x60;mnemonic&#x60; | | any of &#x60;source_sentence&#x60;, &#x60;source_sentence_id&#x60;, &#x60;source_media_id&#x60; | &#x60;source_sentence&#x60; (one register covering all three; the ones not sent are written as &#x60;\&quot;\&quot;&#x60; / &#x60;null&#x60;) | | &#x60;entry_seq&#x60; | &#x60;entry_seq&#x60; (vocab notes only; &#x60;null&#x60; un-matches) |  &#x60;tags&#x60; together with &#x60;add_tags&#x60;/&#x60;remove_tags&#x60; is &#x60;422&#x60;. An empty body is &#x60;422&#x60;. If the merged set (current tags plus &#x60;add_tags&#x60;, minus &#x60;remove_tags&#x60;) would exceed 200 tags (the op limit, &#x60;contracts/sync/common.schema.json#/$defs/tags&#x60;), the response is &#x60;422 validation_failed&#x60; with an error &#x60;{pointer: /add_tags, code: out_of_range}&#x60; and nothing is emitted (C-2). &#x60;note_type_id&#x60;, &#x60;kanji_literal&#x60;, &#x60;grammar_point_id&#x60; and &#x60;origin&#x60; are immutable (sending them is &#x60;422&#x60;).  **Re-match collision.** Setting &#x60;entry_seq&#x60; to an entry already held by another live note is allowed: the op-apply path merges this note into that one (&#x60;note.merged&#x60;, plus &#x60;card.created&#x60;/&#x60;card.merged&#x60; as needed, all emitted by the server as a consequence) and the response is the survivor.  **Merged or deleted target.** A merge loser&#39;s id acts on the survivor. A deleted, unmerged note is &#x60;410 gone&#x60;. 

### Example
```kotlin
// Import classes:
//import app.kotobaya.client.infrastructure.*
//import app.kotobaya.client.models.*

val apiInstance = NotesApi()
val noteId : kotlin.String = 01931f40-1a2b-7c3d-8e4f-000000000a01 // kotlin.String | Note id.
val notePatch : NotePatch = {"fields":{"meaning":"to eat"}} // NotePatch | 
try {
    val result : Note = apiInstance.updateNote(noteId, notePatch)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling NotesApi#updateNote")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling NotesApi#updateNote")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **noteId** | **kotlin.String**| Note id. | |
| **notePatch** | [**NotePatch**](NotePatch.md)|  | |

### Return type

[**Note**](Note.md)

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

