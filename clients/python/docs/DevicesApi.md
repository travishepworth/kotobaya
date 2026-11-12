# kotobaya_client.DevicesApi

All URIs are relative to */api/v1*

Method | HTTP request | Description
------------- | ------------- | -------------
[**create_pairing**](DevicesApi.md#create_pairing) | **POST** /devices/pairings | Create a one-time pairing code (shown as a QR code by the web app).
[**get_device**](DevicesApi.md#get_device) | **GET** /devices/{device_id} | One device.
[**get_own_device**](DevicesApi.md#get_own_device) | **GET** /devices/self | The device the calling token is bound to.
[**list_devices**](DevicesApi.md#list_devices) | **GET** /devices | List devices, including revoked ones and the built-in server device.
[**pair_device**](DevicesApi.md#pair_device) | **POST** /devices/pair | Exchange a pairing code for a long-lived device token.
[**revoke_device**](DevicesApi.md#revoke_device) | **DELETE** /devices/{device_id} | Revoke a device and all its tokens.
[**update_device**](DevicesApi.md#update_device) | **PATCH** /devices/{device_id} | Rename a device or change its push topic.
[**update_own_device**](DevicesApi.md#update_own_device) | **PATCH** /devices/self | Rename the caller&#39;s device or change its push topic.


# **create_pairing**
> Pairing create_pairing()

Create a one-time pairing code (shown as a QR code by the web app).

Returns a code valid for 10 minutes and usable once. Pairing codes are held by the server
(in memory is acceptable in v1: a restart invalidates outstanding codes; the response to
`POST /devices/pair` is then `401 invalid_pairing_code` and the user creates a new code).
Any number of codes may be outstanding. The QR code encodes `pairing_uri` verbatim.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.pairing import Pairing
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
    api_instance = kotobaya_client.DevicesApi(api_client)

    try:
        # Create a one-time pairing code (shown as a QR code by the web app).
        api_response = api_instance.create_pairing()
        print("The response of DevicesApi->create_pairing:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->create_pairing: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**Pairing**](Pairing.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**201** | Pairing code created. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_device**
> Device get_device(device_id)

One device.

Admin only. A device reads its own row through `GET /devices/self`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.device import Device
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
    api_instance = kotobaya_client.DevicesApi(api_client)
    device_id = '01931f3e-0000-7000-8000-0000000000a1' # str | Device id.

    try:
        # One device.
        api_response = api_instance.get_device(device_id)
        print("The response of DevicesApi->get_device:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->get_device: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **device_id** | **str**| Device id. | 

### Return type

[**Device**](Device.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The device. |  -  |
**404** | No such resource. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **get_own_device**
> Device get_own_device()

The device the calling token is bound to.

For tokens bound to a device (`device` tokens, and `personal` tokens created with
`with_device`). Any other credential (session cookie, personal token without a device)
gets `403 device_required`. Needs only `read`, so a phone can read its own row without the
`admin` scope. A revoked device's tokens are invalid, so this never returns a revoked row.
`self` is a literal path segment and takes precedence over `/devices/{device_id}`.


### Example

* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.device import Device
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

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.DevicesApi(api_client)

    try:
        # The device the calling token is bound to.
        api_response = api_instance.get_own_device()
        print("The response of DevicesApi->get_own_device:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->get_own_device: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**Device**](Device.md)

### Authorization

[bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The caller&#39;s device. |  -  |
**403** | The credential is valid but may not do this. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **list_devices**
> DeviceList list_devices()

List devices, including revoked ones and the built-in server device.

Not paginated (a handful of rows). Ordered by `paired_at` ascending, then `id`.

### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.device_list import DeviceList
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
    api_instance = kotobaya_client.DevicesApi(api_client)

    try:
        # List devices, including revoked ones and the built-in server device.
        api_response = api_instance.list_devices()
        print("The response of DevicesApi->list_devices:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->list_devices: %s\n" % e)
```



### Parameters

This endpoint does not need any parameter.

### Return type

[**DeviceList**](DeviceList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | All devices. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **pair_device**
> PairResponse pair_device(pair_request)

Exchange a pairing code for a long-lived device token.

Public (the code is the credential). In one transaction: consumes the code, upserts the
`device` row with the client-generated `device_id`, revokes every earlier token of that
device, inserts an `api_token` of kind `device` (name = device name, scopes
`read`, `write`, `review`, no expiry) and returns the token string - the only time it is
ever shown. Emits the event `device.paired`.

Edge cases:
- **Device id reuse** (contracts/sync/SYNC.md 3.3). A device id is the node id of that
  replica's HLC, so it may be reused only by the same install with its local database
  intact (its `hlc_last` never went backwards). Clients MUST mint a new UUIDv7
  `device_id` on first launch, after a reinstall, after clearing app data and after
  restoring the local database from a backup, and MUST send their existing id only when
  re-pairing an intact install (typically after the device was revoked, or its token
  was lost). The server cannot detect every violation; it checks what it can (next item).
- **`hlc_last` check** (optional field). A client re-pairing an existing `device_id`
  SHOULD send its persisted `sync_state.hlc_last`. Inside the pairing transaction, if
  `hlc_last` is present and is below (byte-wise, SYNC.md 3.1) the greatest `change.hlc`
  logged with `device_id` = this device, the local database went backwards (typically
  an Android Auto Backup restore, or a copied database) and reusing the id could repeat
  HLCs: `422 validation_failed` with `errors[0].pointer = /hlc_last`,
  `errors[0].code = hlc_regressed`. The transaction is rolled back, so the code is
  **not** consumed; the client mints a new UUIDv7 `device_id`, resets its replica state
  as for a new device, and pairs again with the same code. `hlc_last` whose device part
  is not `device_id` is also `422 validation_failed` (`code = invalid_value`). Equal to
  the greatest logged HLC is fine (it is the last op the device minted). Absent, or a
  `device_id` that has never logged an op: no check. The server reads the maximum under
  advisory lock `7033001`, so a concurrent push cannot slip between the check and the commit.
- `device_id` already exists and the request re-pairs that intact install: the row is
  reused, `name`, `kind`, `push_topic` are overwritten, `revoked_at` is cleared,
  `paired_at` is set to now. Ops the device pushed earlier stay; its unpushed outbox can
  now be pushed.
- `device_id` is the server device id: `422 validation_failed`.
- Both checks above run before the code is consumed; on `422` the code stays valid.
- Code unknown, expired or used: `401 invalid_pairing_code`. More than 10 failures from
  one client address in 15 minutes: `429 rate_limited`.
- Two requests race with one code: exactly one succeeds.


### Example


```python
import kotobaya_client
from kotobaya_client.models.pair_request import PairRequest
from kotobaya_client.models.pair_response import PairResponse
from kotobaya_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to /api/v1
# See configuration.py for a list of all supported configuration parameters.
configuration = kotobaya_client.Configuration(
    host = "/api/v1"
)


# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.DevicesApi(api_client)
    pair_request = {"code":"K7Q2M9XD","device_id":"01931f3e-0000-7000-8000-0000000000a1","kind":"android","name":"Pixel 9","push_topic":"https://ntfy.example.net/up-kotobaya-a1b2c3"} # PairRequest | 

    try:
        # Exchange a pairing code for a long-lived device token.
        api_response = api_instance.pair_device(pair_request)
        print("The response of DevicesApi->pair_device:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->pair_device: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **pair_request** | [**PairRequest**](PairRequest.md)|  | 

### Return type

[**PairResponse**](PairResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**201** | Paired. Store &#x60;token&#x60; securely; it cannot be retrieved again. |  -  |
**401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
**422** | Invalid body, the server device id, or &#x60;hlc_last&#x60; below what this device id has already logged. |  -  |
**429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **revoke_device**
> revoke_device(device_id)

Revoke a device and all its tokens.

Sets `device.revoked_at` and `api_token.revoked_at` for every token with this `device_id`
in one transaction. The row is kept (ops reference it). Ops already accepted stay; ops
the device has not pushed yet are lost unless the same intact install is paired again
with the same `device_id` (`POST /devices/pair`). Idempotent: revoking a revoked device returns `204`. The server device
cannot be revoked: `409 not_applicable`. Emits the event `device.revoked`.


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
    api_instance = kotobaya_client.DevicesApi(api_client)
    device_id = '01931f3e-0000-7000-8000-0000000000a1' # str | Device id.

    try:
        # Revoke a device and all its tokens.
        api_instance.revoke_device(device_id)
    except Exception as e:
        print("Exception when calling DevicesApi->revoke_device: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **device_id** | **str**| Device id. | 

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
**204** | Revoked. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_device**
> Device update_device(device_id, device_patch)

Rename a device or change its push topic.

Direct write to the system table `device` (not learning data, no op). Admin only; a device
changes its own row through `PATCH /devices/self`. The server device and revoked devices cannot be patched:
`409 not_applicable`.


### Example

* Api Key Authentication (sessionCookie):
* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.device import Device
from kotobaya_client.models.device_patch import DevicePatch
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
    api_instance = kotobaya_client.DevicesApi(api_client)
    device_id = '01931f3e-0000-7000-8000-0000000000a1' # str | Device id.
    device_patch = {"push_topic":null} # DevicePatch | 

    try:
        # Rename a device or change its push topic.
        api_response = api_instance.update_device(device_id, device_patch)
        print("The response of DevicesApi->update_device:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->update_device: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **device_id** | **str**| Device id. | 
 **device_patch** | [**DevicePatch**](DevicePatch.md)|  | 

### Return type

[**Device**](Device.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated device. |  -  |
**404** | No such resource. |  -  |
**409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **update_own_device**
> Device update_own_device(device_patch)

Rename the caller's device or change its push topic.

Same body and effect as `PATCH /devices/{device_id}`, applied to the device the calling
token is bound to; this is how the phone registers its UnifiedPush endpoint. Needs only
`write`. Credentials not bound to a device get `403 device_required`. Direct write to the
system table `device` (not learning data, no op).


### Example

* Bearer Authentication (bearerAuth):

```python
import kotobaya_client
from kotobaya_client.models.device import Device
from kotobaya_client.models.device_patch import DevicePatch
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

# Configure Bearer authorization: bearerAuth
configuration = kotobaya_client.Configuration(
    access_token = os.environ["BEARER_TOKEN"]
)

# Enter a context with an instance of the API client
with kotobaya_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = kotobaya_client.DevicesApi(api_client)
    device_patch = {"push_topic":"https://ntfy.example.net/up-kotobaya-a1b2c3"} # DevicePatch | 

    try:
        # Rename the caller's device or change its push topic.
        api_response = api_instance.update_own_device(device_patch)
        print("The response of DevicesApi->update_own_device:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling DevicesApi->update_own_device: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **device_patch** | [**DevicePatch**](DevicePatch.md)|  | 

### Return type

[**Device**](Device.md)

### Authorization

[bearerAuth](../README.md#bearerAuth)

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json, application/problem+json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | The updated device. |  -  |
**403** | The credential is valid but may not do this. |  -  |
**422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
**0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

