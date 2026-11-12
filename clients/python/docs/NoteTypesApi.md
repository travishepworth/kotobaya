# kotobaya_client.NoteTypesApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_note_type**](NoteTypesApi.md#create_note_type) | **POST** /note-types | Create a note type.
[**delete_note_type**](NoteTypesApi.md#delete_note_type) | **DELETE** /note-types/{note_type_id} | Delete one of your own note types.
[**get_note_type**](NoteTypesApi.md#get_note_type) | **GET** /note-types/{note_type_id} | One note type.
[**list_note_types**](NoteTypesApi.md#list_note_types) | **GET** /note-types | List note types (built-ins and your own).
[**update_note_type**](NoteTypesApi.md#update_note_type) | **PATCH** /note-types/{note_type_id} | Edit a note type&#39;s name, fields, templates or CSS.


# **create_note_type**
> NoteType create_note_type(note_type_create)

Create a note type.

Server emits `note_type.created`. `base_type` is immutable. `fields` must be non-empty with
unique keys; `templates` must contain at least one card kind. `builtin` is always `false`
for created types. A `vocab` type MUST have field keys `expression`, `reading` and
`meaning` (custom types such as a Takoboto one map their fields onto them); otherwise
`422 validation_failed`, error `invalid_value` at `/fields`. The same holds for a
`PATCH` that replaces a vocab type's `fields` (C-2, OPS.md `note_type.created`).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note_type import NoteType
from kotobaya_client.models.note_type_create import NoteTypeCreate
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
    api_instance = kotobaya_client.NoteTypesApi(api_client)
    note_type_create = {"base_type":"vocab","fields":[{"key":"expression","kind":"text","label":"Expression"},{"key":"reading","kind":"text","label":"Reading"},{"key":"meaning","kind":"text","label":"Meaning"}],"name":"Takoboto Vocab","templates":{"meaning":{"back":"{{meaning}}","default":true,"front":"<div class=\"expr\">{{expression}}</div>"},"reading":{"back":null,"default":true,"front":null}}} # NoteTypeCreate | 

    try:
        # Create a note type.
        api_response = api_instance.create_note_type(note_type_create)
        print("The response of NoteTypesApi->create_note_type:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NoteTypesApi->create_note_type: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_type_create** | [**NoteTypeCreate**](NoteTypeCreate.md)|  | 

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A note type with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_note_type**
> delete_note_type(note_type_id)

Delete one of your own note types.

Server emits `note_type.deleted`. Built-in: `409 builtin_immutable`. Any live note uses
it: `409 in_use` (delete or leave the notes first). Already deleted: `204`, no op.

Notes of a deleted type stay usable (SYNC.md 7.3): an offline device may still create a
note of a type deleted elsewhere (its push is accepted), and deletion is permanent, so
hiding such notes would lose them for good. They are listed, searched, edited (`PATCH
/notes/{id}`) and their cards reviewed like any other; they are rendered with the type's
last `fields`, `templates` and `css`, which `GET /note-types/{id}?include_deleted=true`
returns. The deleted type is omitted from `GET /note-types` (without `include_deleted`)
and `POST /notes` refuses it (a client SHOULD NOT offer it for new notes).


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
    api_instance = kotobaya_client.NoteTypesApi(api_client)
    note_type_id = '00000000-0000-7000-8000-000000000101' # str | Note type id.

    try:
        # Delete one of your own note types.
        api_instance.delete_note_type(note_type_id)
    except Exception as e:
        print("Exception when calling NoteTypesApi->delete_note_type: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_type_id** | **str**| Note type id. | 

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
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_note_type**
> NoteType get_note_type(note_type_id, include_deleted=include_deleted)

One note type.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note_type import NoteType
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
    api_instance = kotobaya_client.NoteTypesApi(api_client)
    note_type_id = '00000000-0000-7000-8000-000000000101' # str | Note type id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One note type.
        api_response = api_instance.get_note_type(note_type_id, include_deleted=include_deleted)
        print("The response of NoteTypesApi->get_note_type:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NoteTypesApi->get_note_type: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_type_id** | **str**| Note type id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The note type. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_note_types**
> NoteTypeList list_note_types(include_deleted=include_deleted)

List note types (built-ins and your own).

Not paginated. Ordered by `builtin` descending, then `created_at`, then `id`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note_type_list import NoteTypeList
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
    api_instance = kotobaya_client.NoteTypesApi(api_client)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # List note types (built-ins and your own).
        api_response = api_instance.list_note_types(include_deleted=include_deleted)
        print("The response of NoteTypesApi->list_note_types:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NoteTypesApi->list_note_types: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**NoteTypeList**](NoteTypeList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Note types. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_note_type**
> NoteType update_note_type(note_type_id, note_type_patch)

Edit a note type's name, fields, templates or CSS.

Server emits one `note_type.updated` with the registers present in the body:
`name`, `fields` (whole array), `css`, and one `templates.<kind>` register per key of
`templates`.

- `fields` replaces the whole array. Existing keys may be reordered and relabelled and new
  keys added. Removing a key is allowed for your own types (note values under that key are
  kept in `note.fields`) and is `409 builtin_immutable` for built-in types.
- `templates` is a partial map: only the kinds present are written. A kind cannot be
  removed once it exists (cards may reference it); set `default: false` to stop creating it.
- Works on built-in types (name, templates and CSS of built-ins may be edited).


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.note_type import NoteType
from kotobaya_client.models.note_type_patch import NoteTypePatch
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
    api_instance = kotobaya_client.NoteTypesApi(api_client)
    note_type_id = '00000000-0000-7000-8000-000000000101' # str | Note type id.
    note_type_patch = {"css":".expr { font-size: 3rem; }"} # NoteTypePatch | 

    try:
        # Edit a note type's name, fields, templates or CSS.
        api_response = api_instance.update_note_type(note_type_id, note_type_patch)
        print("The response of NoteTypesApi->update_note_type:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling NoteTypesApi->update_note_type: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **note_type_id** | **str**| Note type id. | 
 **note_type_patch** | [**NoteTypePatch**](NoteTypePatch.md)|  | 

### Return type

[**NoteType**](NoteType.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated note type. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

