# kotobaya_client.GrammarApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_grammar_point**](GrammarApi.md#create_grammar_point) | **POST** /grammar | Create a grammar point.
[**delete_grammar_point**](GrammarApi.md#delete_grammar_point) | **DELETE** /grammar/{grammar_point_id} | Delete a grammar point.
[**get_grammar_point**](GrammarApi.md#get_grammar_point) | **GET** /grammar/{grammar_point_id} | One grammar point.
[**list_grammar_examples**](GrammarApi.md#list_grammar_examples) | **GET** /grammar/{grammar_point_id}/examples | Sentences where the grammar point was seen (detected or highlighted by hand).
[**list_grammar_points**](GrammarApi.md#list_grammar_points) | **GET** /grammar | List or search the grammar library.
[**put_grammar_span**](GrammarApi.md#put_grammar_span) | **PUT** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Highlight a span of a sentence as an example of this grammar point (\&quot;I saw something weird\&quot;).
[**remove_grammar_span**](GrammarApi.md#remove_grammar_span) | **DELETE** /grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset} | Remove a manual span (reversible by putting it again).
[**test_grammar_rules**](GrammarApi.md#test_grammar_rules) | **POST** /grammar/{grammar_point_id}/rules/test | Run detection rules against your sentences without saving anything.
[**update_grammar_point**](GrammarApi.md#update_grammar_point) | **PATCH** /grammar/{grammar_point_id} | Edit a grammar point.


# **create_grammar_point**
> GrammarPoint create_grammar_point(grammar_point_create)

Create a grammar point.

Server emits `grammar.created`. Only `title` is required. Each `detection_rules[].rule`
must parse in the token-pattern language (`422` with the rule's pointer otherwise).
`related_ids` are soft references and are not checked. Creating the point does not create
a grammar note or card: do that with `POST /notes` (`grammar_point_id`). Non-empty
`detection_rules` make the server enqueue re-detection of all media (a `grammar-detect`
job, C-13; see `Job`); media parse and import jobs also detect.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_point import GrammarPoint
from kotobaya_client.models.grammar_point_create import GrammarPointCreate
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_create = {"detection_rules":[{"draft":false,"rule":"[verb:未然形] ざる を 得 ない"}],"id":"01931f40-4000-7000-8000-000000000901","jlpt":2,"meaning":"cannot help but; have no choice but to","pattern":"〜ざるを得ない","title":"ざるを得ない"} # GrammarPointCreate | 

    try:
        # Create a grammar point.
        api_response = api_instance.create_grammar_point(grammar_point_create)
        print("The response of GrammarApi->create_grammar_point:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->create_grammar_point: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_create** | [**GrammarPointCreate**](GrammarPointCreate.md)|  | 

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A grammar point with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_grammar_point**
> delete_grammar_point(grammar_point_id)

Delete a grammar point.

Server emits `grammar.deleted`. Its grammar note (if any) is not deleted and keeps
working from its own fields; spans stay and are hidden by readers. Already deleted:
`204`, no op.


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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.

    try:
        # Delete a grammar point.
        api_instance.delete_grammar_point(grammar_point_id)
    except Exception as e:
        print("Exception when calling GrammarApi->delete_grammar_point: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 

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

# **get_grammar_point**
> GrammarPoint get_grammar_point(grammar_point_id, include_deleted=include_deleted)

One grammar point.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_point import GrammarPoint
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One grammar point.
        api_response = api_instance.get_grammar_point(grammar_point_id, include_deleted=include_deleted)
        print("The response of GrammarApi->get_grammar_point:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->get_grammar_point: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The grammar point. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_grammar_examples**
> SentencePage list_grammar_examples(grammar_point_id, origin=origin, filter=filter, limit=limit, cursor=cursor)

Sentences where the grammar point was seen (detected or highlighted by hand).

Sentences having at least one live span for this point. Spans whose sentence no longer
exists are skipped. Ordered: manual spans first, then `i+0` sentences (no unknown
words), then by unknown-word count ascending, then sentence id. Each sentence's
`grammar_spans` lists all its spans; those of this point are the examples.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.sentence_page import SentencePage
from kotobaya_client.models.span_origin import SpanOrigin
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    origin = kotobaya_client.SpanOrigin() # SpanOrigin | Only sentences having a span of this origin for the point. (optional)
    filter = 'i+1' # str | `all` (default); `i+1` = sentences with exactly one unknown word; `i+0` = none. \"Unknown word\" = a distinct entry among the sentence's tokens whose effective word status is `new`; tokens of class `particle`, `auxiliary`, `name`, unmapped tokens and entries with status `blacklisted`/`ignored` are not counted. Because an unencoded `+` in a query string decodes to a space, servers MUST also accept `i 1` and `i 0`.  (optional)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # Sentences where the grammar point was seen (detected or highlighted by hand).
        api_response = api_instance.list_grammar_examples(grammar_point_id, origin=origin, filter=filter, limit=limit, cursor=cursor)
        print("The response of GrammarApi->list_grammar_examples:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->list_grammar_examples: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **origin** | [**SpanOrigin**](.md)| Only sentences having a span of this origin for the point. | [optional] 
 **filter** | **str**| &#x60;all&#x60; (default); &#x60;i+1&#x60; &#x3D; sentences with exactly one unknown word; &#x60;i+0&#x60; &#x3D; none. \&quot;Unknown word\&quot; &#x3D; a distinct entry among the sentence&#39;s tokens whose effective word status is &#x60;new&#x60;; tokens of class &#x60;particle&#x60;, &#x60;auxiliary&#x60;, &#x60;name&#x60;, unmapped tokens and entries with status &#x60;blacklisted&#x60;/&#x60;ignored&#x60; are not counted. Because an unencoded &#x60;+&#x60; in a query string decodes to a space, servers MUST also accept &#x60;i 1&#x60; and &#x60;i 0&#x60;.  | [optional] 
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**SentencePage**](SentencePage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of sentences. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_grammar_points**
> GrammarPointPage list_grammar_points(q=q, jlpt=jlpt, is_stub=is_stub, include_deleted=include_deleted, limit=limit, cursor=cursor)

List or search the grammar library.

Ordered by `jlpt` descending with NULLs last (easiest first), then `title`, then `id`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_point_page import GrammarPointPage
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    q = 'ざるを得ない' # str | Case-insensitive substring of title, pattern or meaning. (optional)
    jlpt = 2 # int | Only points of this N-level. (optional)
    is_stub = false # bool | Only stubs (`true`) or only filled-in points (`false`). (optional)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)
    limit = 50 # int | Page size. (optional) (default to 50)
    cursor = 'eyJrIjpbIjIwMjYtMTEtMTJUMDk6MzA6MDAuMDAwWiIsIjAxOTMxZjQwIl19' # str | Opaque `next_cursor` from the previous page. Absent = first page. A malformed or foreign cursor is `422`. (optional)

    try:
        # List or search the grammar library.
        api_response = api_instance.list_grammar_points(q=q, jlpt=jlpt, is_stub=is_stub, include_deleted=include_deleted, limit=limit, cursor=cursor)
        print("The response of GrammarApi->list_grammar_points:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->list_grammar_points: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **q** | **str**| Case-insensitive substring of title, pattern or meaning. | [optional] 
 **jlpt** | **int**| Only points of this N-level. | [optional] 
 **is_stub** | **bool**| Only stubs (&#x60;true&#x60;) or only filled-in points (&#x60;false&#x60;). | [optional] 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]
 **limit** | **int**| Page size. | [optional] [default to 50]
 **cursor** | **str**| Opaque &#x60;next_cursor&#x60; from the previous page. Absent &#x3D; first page. A malformed or foreign cursor is &#x60;422&#x60;. | [optional] 

### Return type

[**GrammarPointPage**](GrammarPointPage.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | One page of grammar points. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **put_grammar_span**
> GrammarSpan put_grammar_span(grammar_point_id, sentence_id, start_offset, end_offset, grammar_span_put=grammar_span_put)

Highlight a span of a sentence as an example of this grammar point (\"I saw something weird\").

Server emits `grammar.span_put` for the manual span
`(sentence_id, grammar_point_id, start_offset, end_offset, manual)`. Putting a span that
exists (live or removed) is fine: it becomes live. If the sentence exists, `end_offset`
beyond its text length is `422` and `media_id` is filled in from the sentence; if it
does not exist the span is stored as given with the body's `media_id` (or null). The
grammar point must be live (`410` if deleted). To create a new point from a highlight,
call `POST /grammar` first, then this.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_span import GrammarSpan
from kotobaya_client.models.grammar_span_put import GrammarSpanPut
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    sentence_id = '01931f40-5000-7000-8000-000000000701' # str | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
    start_offset = 3 # int | UTF-16 code-unit offset into the sentence text, inclusive.
    end_offset = 9 # int | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).
    grammar_span_put = {} # GrammarSpanPut |  (optional)

    try:
        # Highlight a span of a sentence as an example of this grammar point (\"I saw something weird\").
        api_response = api_instance.put_grammar_span(grammar_point_id, sentence_id, start_offset, end_offset, grammar_span_put=grammar_span_put)
        print("The response of GrammarApi->put_grammar_span:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->put_grammar_span: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **sentence_id** | **str**| Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | 
 **start_offset** | **int**| UTF-16 code-unit offset into the sentence text, inclusive. | 
 **end_offset** | **int**| UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | 
 **grammar_span_put** | [**GrammarSpanPut**](GrammarSpanPut.md)|  | [optional] 

### Return type

[**GrammarSpan**](GrammarSpan.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The span as stored. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **remove_grammar_span**
> remove_grammar_span(grammar_point_id, sentence_id, start_offset, end_offset)

Remove a manual span (reversible by putting it again).

Server emits `grammar.span_removed`. No live manual span with this key: `204`, no op.
Detected spans cannot be removed this way (they are rebuilt by parse jobs; fix the rule
instead).


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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    sentence_id = '01931f40-5000-7000-8000-000000000701' # str | Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged).
    start_offset = 3 # int | UTF-16 code-unit offset into the sentence text, inclusive.
    end_offset = 9 # int | UTF-16 code-unit offset, exclusive. Must be greater than `start_offset` (`422`).

    try:
        # Remove a manual span (reversible by putting it again).
        api_instance.remove_grammar_span(grammar_point_id, sentence_id, start_offset, end_offset)
    except Exception as e:
        print("Exception when calling GrammarApi->remove_grammar_span: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **sentence_id** | **str**| Sentence the span is in. Soft reference - it need not exist on the server (it may have been purged). | 
 **start_offset** | **int**| UTF-16 code-unit offset into the sentence text, inclusive. | 
 **end_offset** | **int**| UTF-16 code-unit offset, exclusive. Must be greater than &#x60;start_offset&#x60; (&#x60;422&#x60;). | 

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
**204** | Removed. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **test_grammar_rules**
> GrammarRuleTestResult test_grammar_rules(grammar_point_id, grammar_rule_test_request=grammar_rule_test_request)

Run detection rules against your sentences without saving anything.

Evaluates `rules` (default: the point's stored `detection_rules`) over stored sentence
tokens and returns up to `limit` matching sentences with the matched spans, scanning
media newest first, sentences in order. Read-only: no spans are written, no op is
emitted. A rule that does not parse is `422` with its pointer. The scan stops after
`limit` matches or 200,000 sentences, whichever comes first; `scanned` and `truncated`
say how far it got.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_rule_test_request import GrammarRuleTestRequest
from kotobaya_client.models.grammar_rule_test_result import GrammarRuleTestResult
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    grammar_rule_test_request = {"limit":20,"media_id":"01931f40-3000-7000-8000-000000000e01","rules":[{"draft":true,"rule":"[verb:未然形] ざる を 得 ない"}]} # GrammarRuleTestRequest |  (optional)

    try:
        # Run detection rules against your sentences without saving anything.
        api_response = api_instance.test_grammar_rules(grammar_point_id, grammar_rule_test_request=grammar_rule_test_request)
        print("The response of GrammarApi->test_grammar_rules:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->test_grammar_rules: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **grammar_rule_test_request** | [**GrammarRuleTestRequest**](GrammarRuleTestRequest.md)|  | [optional] 

### Return type

[**GrammarRuleTestResult**](GrammarRuleTestResult.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Matches. |  -  |
**404** | No such resource. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_grammar_point**
> GrammarPoint update_grammar_point(grammar_point_id, grammar_point_patch)

Edit a grammar point.

Server emits one `grammar.updated` with the registers present: `title`, `pattern`,
`meaning`, `explanation`, `notes`, `jlpt`, `detection_rules` (whole array), `related_ids`
(whole array), `links` (whole array), `is_stub`. Editing any text property of a stub does
not clear `is_stub` by itself; send `is_stub: false`. Empty body: `422`.
A winning `detection_rules` write makes the server enqueue re-detection of all media
(a `grammar-detect` job, C-13; see `Job`), in the transaction that applies the op.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.grammar_point import GrammarPoint
from kotobaya_client.models.grammar_point_patch import GrammarPointPatch
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
    api_instance = kotobaya_client.GrammarApi(api_client)
    grammar_point_id = '01931f40-4000-7000-8000-000000000901' # str | Grammar point id.
    grammar_point_patch = {"explanation":"Formal. Attaches to the 未然形; する becomes せざる.","is_stub":false} # GrammarPointPatch | 

    try:
        # Edit a grammar point.
        api_response = api_instance.update_grammar_point(grammar_point_id, grammar_point_patch)
        print("The response of GrammarApi->update_grammar_point:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling GrammarApi->update_grammar_point: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **grammar_point_id** | **str**| Grammar point id. | 
 **grammar_point_patch** | [**GrammarPointPatch**](GrammarPointPatch.md)|  | 

### Return type

[**GrammarPoint**](GrammarPoint.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated grammar point. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

