# kotobaya_client.NotesApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_note**](NotesApi.md#create_note) | **POST** /notes | Create a note and its cards.
[**delete_note**](NotesApi.md#delete_note) | **DELETE** /notes/{note_id} | Delete a note (permanent tombstone; hides all its cards).
[**get_note**](NotesApi.md#get_note) | **GET** /notes/{note_id} | One note with its live cards.
[**list_notes**](NotesApi.md#list_notes) | **GET** /notes | List or search notes.
[**update_note**](NotesApi.md#update_note) | **PATCH** /notes/{note_id} | Edit a note&#39;s fields, tags, mnemonic, source sentence or dictionary match.


# **create_note**
> Note create_note(note_create, on_duplicate=on_duplicate)

Create a note and its cards.

Server emits, in one transaction and in this order: `note.created`; one `card.created`
per card kind (server-generated card ids; kinds = `card_kinds` if given, else the note
type's kinds with `default: true`, in ascending kind order); `deck.item_put` if `deck_id`
is given (item key `e:<entry_seq>` when the note has an `entry_seq`, else `n:<note id>`,
position = current maximum position in the deck + 1024).

Rules:
- `note_type_id` must be a live note type. `type` is derived from it.
- At most one of `entry_seq`, `kanji_literal`, `grammar_point_id`, and only the one that
  fits the base type (`vocab`, `kanji`, `grammar` respectively); otherwise `422`.
  `entry_seq` must exist in `ref.dict_entry` (retired entries are accepted);
  `kanji_literal` need not exist in `ref.kanji`; `grammar_point_id` must be a live
  grammar point.
- Unknown keys in `fields` are stored as given. Missing keys mean `""`.
- `tags`: each 1 to 64 characters, no whitespace, not `leech`; stored de-duplicated and
  sorted ascending.
- `card_kinds` entries must be keys of the note type's `templates`; an empty array
  creates a note without cards.
- `deck_id` must be a live deck of type `user`; otherwise `409 deck_read_only`.

**Duplicate reference.** If a live note already references the same entry, kanji or
grammar point, nothing is created: with `on_duplicate=error` (default) the response is
`409 already_exists` with `existing_id`; with `on_duplicate=return_existing` it is `200`
with the existing note, unchanged (if `deck_id` was given the `deck.item_put` is still
emitted). REST never creates a duplicate on purpose; duplicates created offline are
merged by the op-apply path (SCHEMA.md 4.2).

**Idempotency.** An `id` that already exists (live, deleted or merged) returns that note
(or its survivor) with `200` and emits nothing.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note import Note
from kotobaya_client.models.note_create import NoteCreate
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.NotesApi(api_client)
    note_create = {"card_kinds":["basic"],"fields":{"back":"four-character idiom","front":"四字熟語"},"note_type_id":"00000000-0000-7000-8000-000000000106"} # NoteCreate | 
    on_duplicate = 'return_existing' # str | What to do when a live note already references the same entry, kanji or grammar point. (optional)

    try:
        # Create a note and its cards.
        api_response = api_instance.create_note(note_create, on_duplicate=on_duplicate)
        print("The response of NotesApi->create_note:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NotesApi->create_note: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_create** | [**NoteCreate**](NoteCreate.md)|  | 
 **on_duplicate** | **str**| What to do when a live note already references the same entry, kanji or grammar point. | [optional] 

### Return type

[**Note**](Note.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Nothing created - the &#x60;id&#x60; already existed, or &#x60;on_duplicate&#x3D;return_existing&#x60; matched. Body is the existing note. |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**409** | A natural-key collision; &#x60;existing_id&#x60; names the row that holds the key. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_note**
> delete_note(note_id)

Delete a note (permanent tombstone; hides all its cards).

Server emits `note.deleted`. Cards are hidden through the note's tombstone; no
`card.deleted` ops are emitted. Reviews stay in the log. Word status `auto_status` is
re-derived. Already deleted, or the id of a merge loser (deleting a duplicate never
deletes the survivor): `204`, no op.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.NotesApi(api_client)
    note_id = '01931f40-1a2b-7c3d-8e4f-000000000a01' # str | Note id.

    try:
        # Delete a note (permanent tombstone; hides all its cards).
        api_instance.delete_note(note_id)
    except Exception as e:
        print("Exception when calling NotesApi->delete_note: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_id** | **str**| Note id. | 

### Return type

void (empty response body)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**204** | Deleted. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_note**
> Note get_note(note_id, include_deleted=include_deleted)

One note with its live cards.

If the id belongs to a merge loser the survivor is returned (status `200`,
`Content-Location: /api/v1/notes/<survivor id>`), with or without `include_deleted`.
A deleted, unmerged note is `410 gone` unless `include_deleted=true`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note import Note
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.NotesApi(api_client)
    note_id = '01931f40-1a2b-7c3d-8e4f-000000000a01' # str | Note id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One note with its live cards.
        api_response = api_instance.get_note(note_id, include_deleted=include_deleted)
        print("The response of NotesApi->get_note:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NotesApi->get_note: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_id** | **str**| Note id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**Note**](Note.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The note (or its merge survivor). |  * Content-Location -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_notes**
> NotePage list_notes(q=q, type=type, note_type_id=note_type_id, tag=tag, entry_seq=entry_seq, kanji_literal=kanji_literal, grammar_point_id=grammar_point_id, updated_since=updated_since, include_deleted=include_deleted, limit=limit, cursor=cursor)

List or search notes.

All filters combine with AND. Ordered by `created_at` descending, then `id` descending.
Merge losers appear only with `include_deleted=true`, as tombstones with
`merged_into_id` set. Each note embeds its live cards.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note_base_type import NoteBaseType
from kotobaya_client.models.note_page import NotePage
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.NotesApi(api_client)
    q = '食べ' # str | Case-insensitive substring of any field value, the mnemonic or the source sentence (`note.search_text`). (optional)
    type = kotobaya_client.NoteBaseType() # NoteBaseType | Only notes of this base type. (optional)
    note_type_id = '00000000-0000-7000-8000-000000000101' # str | Only notes of this note type. (optional)
    tag = 'anime' # str | Only notes carrying this exact tag. The virtual tag `leech` is not accepted here (use `GET /cards?query=tag:leech`). (optional)
    entry_seq = 1358280 # int | Only the (at most one live) note referencing this entry. (optional)
    kanji_literal = '績' # str | Only the kanji note for this kanji. (optional)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Only the grammar note for this grammar point. (optional)
    updated_since = '2026-11-01T00:00:00.000Z' # datetime | Only notes with `updated_at` strictly after this instant. (optional)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # List or search notes.
        api_response = api_instance.list_notes(q=q, type=type, note_type_id=note_type_id, tag=tag, entry_seq=entry_seq, kanji_literal=kanji_literal, grammar_point_id=grammar_point_id, updated_since=updated_since, include_deleted=include_deleted, limit=limit, cursor=cursor)
        print("The response of NotesApi->list_notes:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NotesApi->list_notes: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **q** | **str**| Case-insensitive substring of any field value, the mnemonic or the source sentence (&#x60;note.search_text&#x60;). | [optional] 
 **type** | [**NoteBaseType**](.md)| Only notes of this base type. | [optional] 
 **note_type_id** | **str**| Only notes of this note type. | [optional] 
 **tag** | **str**| Only notes carrying this exact tag. The virtual tag &#x60;leech&#x60; is not accepted here (use &#x60;GET /cards?query&#x3D;tag:leech&#x60;). | [optional] 
 **entry_seq** | **int**| Only the (at most one live) note referencing this entry. | [optional] 
 **kanji_literal** | **str**| Only the kanji note for this kanji. | [optional] 
 **grammar_point_id** | **str**| Only the grammar note for this grammar point. | [optional] 
 **updated_since** | **datetime**| Only notes with &#x60;updated_at&#x60; strictly after this instant. | [optional] 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**NotePage**](NotePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of notes. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_note**
> Note update_note(note_id, note_patch)

Edit a note's fields, tags, mnemonic, source sentence or dictionary match.

Server emits one `note.updated` carrying exactly the registers the body touches:

| Body property | Register(s) written |
|---|---|
| `fields.<key>` | `fields.<key>` for each key present (`""` clears the field) |
| `tags` | `tags` (whole set) |
| `add_tags` / `remove_tags` | `tags`: current set plus/minus the given tags, computed by the server at apply time |
| `mnemonic` | `mnemonic` |
| any of `source_sentence`, `source_sentence_id`, `source_media_id` | `source_sentence` (one register covering all three; the ones not sent are written as `""` / `null`) |
| `entry_seq` | `entry_seq` (vocab notes only; `null` un-matches) |

`tags` together with `add_tags`/`remove_tags` is `422`. An empty body is `422`.
If the merged set (current tags plus `add_tags`, minus `remove_tags`) would exceed 200
tags (the op limit, `contracts/sync/common.schema.json#/$defs/tags`), the response is
`422 validation_failed` with an error `{pointer: /add_tags, code: out_of_range}` and
nothing is emitted (C-2).
`note_type_id`, `kanji_literal`, `grammar_point_id` and `origin` are immutable (sending
them is `422`).

**Re-match collision.** Setting `entry_seq` to an entry already held by another live note
is allowed: the op-apply path merges this note into that one (`note.merged`, plus
`card.created`/`card.merged` as needed, all emitted by the server as a consequence) and
the response is the survivor.

**Merged or deleted target.** A merge loser's id acts on the survivor. A deleted,
unmerged note is `410 gone`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note import Note
from kotobaya_client.models.note_patch import NotePatch
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)

# The client must configure the authentication and authorization parameters
# in accordance with the API server security policy.
# Examples for each auth method are provided below, use the example that
# satisfies your auth use case.

# Configure API key authorization: sessionCookie
configuration.api_key['sessionCookie'] = os.environ["API_KEY"]

# Uncomment below to setup prefix (e.g. Bearer) for API key, if needed
# configuration.api_key_prefix['sessionCookie'] = 'Bearer'

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.NotesApi(api_client)
    note_id = '01931f40-1a2b-7c3d-8e4f-000000000a01' # str | Note id.
    note_patch = {"fields":{"meaning":"to eat"}} # NotePatch | 

    try:
        # Edit a note's fields, tags, mnemonic, source sentence or dictionary match.
        api_response = api_instance.update_note(note_id, note_patch)
        print("The response of NotesApi->update_note:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NotesApi->update_note: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_id** | **str**| Note id. | 
 **note_patch** | [**NotePatch**](NotePatch.md)|  | 

### Return type

[**Note**](Note.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated note (or the survivor after a merge). |  * Content-Location -  <br>  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

