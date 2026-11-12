# kotobaya_client.PresetsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_preset**](PresetsApi.md#create_preset) | **POST** /presets | Create a preset.
[**delete_preset**](PresetsApi.md#delete_preset) | **DELETE** /presets/{preset_id} | Delete a preset.
[**get_preset**](PresetsApi.md#get_preset) | **GET** /presets/{preset_id} | One preset.
[**list_presets**](PresetsApi.md#list_presets) | **GET** /presets | List scheduling presets.
[**optimize_preset**](PresetsApi.md#optimize_preset) | **POST** /presets/{preset_id}/optimize | Queue an FSRS optimizer run for this preset.
[**update_preset**](PresetsApi.md#update_preset) | **PATCH** /presets/{preset_id} | Change preset options.


# **create_preset**
> Preset create_preset(preset_create)

Create a preset.

Server emits `fsrs_params.created`. Omitted properties take the column defaults. Weights
cannot be set through the API; they are published by the optimizer job.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.preset import Preset
from kotobaya_client.models.preset_create import PresetCreate
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
    api_instance = kotobaya_client.PresetsApi(api_client)
    preset_create = {"desired_retention":0.85,"learning_steps_s":[60,600,3600],"name":"Kanji"} # PresetCreate | 

    try:
        # Create a preset.
        api_response = api_instance.create_preset(preset_create)
        print("The response of PresetsApi->create_preset:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling PresetsApi->create_preset: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **preset_create** | [**PresetCreate**](PresetCreate.md)|  | 

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A preset with this &#x60;id&#x60; already existed; returned unchanged. |  -  |
**201** | Created. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **delete_preset**
> delete_preset(preset_id)

Delete a preset.

Server emits `fsrs_params.deleted`. Cards pointing at a deleted preset are scheduled with
the Default preset (their `preset_id` is not rewritten). The Default preset
(`00000000-0000-7000-8000-000000000010`) cannot be deleted: `409 builtin_immutable`.
Already deleted: `204`, no op.


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
    api_instance = kotobaya_client.PresetsApi(api_client)
    preset_id = '00000000-0000-7000-8000-000000000010' # str | Preset id (`fsrs_params.id`).

    try:
        # Delete a preset.
        api_instance.delete_preset(preset_id)
    except Exception as e:
        print("Exception when calling PresetsApi->delete_preset: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **preset_id** | **str**| Preset id (&#x60;fsrs_params.id&#x60;). | 

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

# **get_preset**
> Preset get_preset(preset_id, include_deleted=include_deleted)

One preset.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.preset import Preset
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
    api_instance = kotobaya_client.PresetsApi(api_client)
    preset_id = '00000000-0000-7000-8000-000000000010' # str | Preset id (`fsrs_params.id`).
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # One preset.
        api_response = api_instance.get_preset(preset_id, include_deleted=include_deleted)
        print("The response of PresetsApi->get_preset:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling PresetsApi->get_preset: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **preset_id** | **str**| Preset id (&#x60;fsrs_params.id&#x60;). | 
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The preset. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_presets**
> PresetList list_presets(include_deleted=include_deleted)

List scheduling presets.

Not paginated. Ordered by `created_at`, then `id` (the Default preset first).

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.preset_list import PresetList
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
    api_instance = kotobaya_client.PresetsApi(api_client)
    include_deleted = False # bool | Also return tombstoned rows (`deleted_at` set). (optional) (default to False)

    try:
        # List scheduling presets.
        api_response = api_instance.list_presets(include_deleted=include_deleted)
        print("The response of PresetsApi->list_presets:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling PresetsApi->list_presets: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **include_deleted** | **bool**| Also return tombstoned rows (&#x60;deleted_at&#x60; set). | [optional] [default to False]

### Return type

[**PresetList**](PresetList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Presets. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **optimize_preset**
> Job optimize_preset(preset_id)

Queue an FSRS optimizer run for this preset.

Enqueues a job of kind `fsrs-optimize` (payload `JobPayloadFsrsOptimize`, dedupe key
`fsrs-optimize:<preset id>`). If a queued or running job with that key exists it is
returned with `200` instead of creating another. When the job succeeds the worker
publishes the weights: the server emits `fsrs_params.published` (not this request).
The weekly schedule enqueues the same job for every live preset.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.job import Job
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
    api_instance = kotobaya_client.PresetsApi(api_client)
    preset_id = '00000000-0000-7000-8000-000000000010' # str | Preset id (`fsrs_params.id`).

    try:
        # Queue an FSRS optimizer run for this preset.
        api_response = api_instance.optimize_preset(preset_id)
        print("The response of PresetsApi->optimize_preset:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling PresetsApi->optimize_preset: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **preset_id** | **str**| Preset id (&#x60;fsrs_params.id&#x60;). | 

### Return type

[**Job**](Job.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | An equivalent job is already queued or running. |  -  |
**202** | Job queued. |  -  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_preset**
> Preset update_preset(preset_id, preset_patch)

Change preset options.

Server emits one `fsrs_params.updated` carrying the registers present in the body. Changing
any option makes every card cache on the preset stale; replicas recompute (SCHEMA.md 4.2).
Patching a deleted preset: `410 gone`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.preset import Preset
from kotobaya_client.models.preset_patch import PresetPatch
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
    api_instance = kotobaya_client.PresetsApi(api_client)
    preset_id = '00000000-0000-7000-8000-000000000010' # str | Preset id (`fsrs_params.id`).
    preset_patch = {"easy_days":[1,1,1,1,1,1,0.5]} # PresetPatch | 

    try:
        # Change preset options.
        api_response = api_instance.update_preset(preset_id, preset_patch)
        print("The response of PresetsApi->update_preset:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling PresetsApi->update_preset: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **preset_id** | **str**| Preset id (&#x60;fsrs_params.id&#x60;). | 
 **preset_patch** | [**PresetPatch**](PresetPatch.md)|  | 

### Return type

[**Preset**](Preset.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated preset. |  * Kotobaya-Seq -  <br>  |
**404** | No such resource. |  -  |
**410** | The row is a tombstone. Repeat with &#x60;include_deleted&#x3D;true&#x60; to read it. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

