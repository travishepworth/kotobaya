# kotobaya_client.SettingsApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**list_settings**](SettingsApi.md#list_settings) | **GET** /settings | All stored settings.
[**update_settings**](SettingsApi.md#update_settings) | **PATCH** /settings | Set one or more settings.


# **list_settings**
> SettingList list_settings()

All stored settings.

Returns one entry per `app.settings` row whose value is not JSON `null`, ordered by `key`.
Keys never written are absent: their defaults live in the shared core, not on the server.
Well-known keys are listed in `contracts/db/SCHEMA.md` section 6; unknown keys are stored
and returned untouched.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.setting_list import SettingList
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
    api_instance = kotobaya_client.SettingsApi(api_client)

    try:
        # All stored settings.
        api_response = api_instance.list_settings()
        print("The response of SettingsApi->list_settings:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling SettingsApi->list_settings: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**SettingList**](SettingList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | Stored settings. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_settings**
> SettingList update_settings(settings_patch)

Set one or more settings.

Server emits one `settings.set` op per key in `values`, in ascending key order, all in one
transaction. A JSON `null` value resets the key to its default (the row is kept with value
`null` so the reset wins against older writes). Keys must match
`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`. Values of well-known keys are validated against
the types in SCHEMA.md section 6 (`422` with `pointer` `/values/<key>`); values of unknown
keys are accepted as any JSON. An empty `values` object is `422`.
The response is the full list as `GET /settings` returns it after the change.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.setting_list import SettingList
from kotobaya_client.models.settings_patch import SettingsPatch
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
    api_instance = kotobaya_client.SettingsApi(api_client)
    settings_patch = {"values":{"scheduler.leech_threshold":null}} # SettingsPatch | 

    try:
        # Set one or more settings.
        api_response = api_instance.update_settings(settings_patch)
        print("The response of SettingsApi->update_settings:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling SettingsApi->update_settings: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **settings_patch** | [**SettingsPatch**](SettingsPatch.md)|  | 

### Return type

[**SettingList**](SettingList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | All stored settings after the change. |  * Kotobaya-Seq -  <br>  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

