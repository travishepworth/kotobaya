# DevicesApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**createPairing**](DevicesApi.md#createpairing) | **POST** /devices/pairings | Create a one-time pairing code (shown as a QR code by the web app). |
| [**getDevice**](DevicesApi.md#getdevice) | **GET** /devices/{device_id} | One device. |
| [**getOwnDevice**](DevicesApi.md#getowndevice) | **GET** /devices/self | The device the calling token is bound to. |
| [**listDevices**](DevicesApi.md#listdevices) | **GET** /devices | List devices, including revoked ones and the built-in server device. |
| [**pairDevice**](DevicesApi.md#pairdevice) | **POST** /devices/pair | Exchange a pairing code for a long-lived device token. |
| [**revokeDevice**](DevicesApi.md#revokedevice) | **DELETE** /devices/{device_id} | Revoke a device and all its tokens. |
| [**updateDevice**](DevicesApi.md#updatedevice) | **PATCH** /devices/{device_id} | Rename a device or change its push topic. |
| [**updateOwnDevice**](DevicesApi.md#updateowndevice) | **PATCH** /devices/self | Rename the caller\&#39;s device or change its push topic. |



## createPairing

> Pairing createPairing()

Create a one-time pairing code (shown as a QR code by the web app).

Returns a code valid for 10 minutes and usable once. Pairing codes are held by the server (in memory is acceptable in v1: a restart invalidates outstanding codes; the response to &#x60;POST /devices/pair&#x60; is then &#x60;401 invalid_pairing_code&#x60; and the user creates a new code). Any number of codes may be outstanding. The QR code encodes &#x60;pairing_uri&#x60; verbatim. 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { CreatePairingRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  try {
    const data = await api.createPairing();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**Pairing**](Pairing.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Pairing code created. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getDevice

> Device getDevice(deviceId)

One device.

Admin only. A device reads its own row through &#x60;GET /devices/self&#x60;.

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { GetDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  const body = {
    // string | Device id.
    deviceId: 01931f3e-0000-7000-8000-0000000000a1,
  } satisfies GetDeviceRequest;

  try {
    const data = await api.getDevice(body);
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
| **deviceId** | `string` | Device id. | [Defaults to `undefined`] |

### Return type

[**Device**](Device.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The device. |  -  |
| **404** | No such resource. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## getOwnDevice

> Device getOwnDevice()

The device the calling token is bound to.

For tokens bound to a device (&#x60;device&#x60; tokens, and &#x60;personal&#x60; tokens created with &#x60;with_device&#x60;). Any other credential (session cookie, personal token without a device) gets &#x60;403 device_required&#x60;. Needs only &#x60;read&#x60;, so a phone can read its own row without the &#x60;admin&#x60; scope. A revoked device\&#39;s tokens are invalid, so this never returns a revoked row. &#x60;self&#x60; is a literal path segment and takes precedence over &#x60;/devices/{device_id}&#x60;. 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { GetOwnDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  try {
    const data = await api.getOwnDevice();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**Device**](Device.md)

### Authorization

[bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The caller\&#39;s device. |  -  |
| **403** | The credential is valid but may not do this. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## listDevices

> DeviceList listDevices()

List devices, including revoked ones and the built-in server device.

Not paginated (a handful of rows). Ordered by &#x60;paired_at&#x60; ascending, then &#x60;id&#x60;.

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { ListDevicesRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  try {
    const data = await api.listDevices();
    console.log(data);
  } catch (error) {
    console.error(error);
  }
}

// Run the test
example().catch(console.error);
```

### Parameters

This endpoint does not need any parameter.

### Return type

[**DeviceList**](DeviceList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | All devices. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## pairDevice

> PairResponse pairDevice(pairRequest)

Exchange a pairing code for a long-lived device token.

Public (the code is the credential). In one transaction: consumes the code, upserts the &#x60;device&#x60; row with the client-generated &#x60;device_id&#x60;, revokes every earlier token of that device, inserts an &#x60;api_token&#x60; of kind &#x60;device&#x60; (name &#x3D; device name, scopes &#x60;read&#x60;, &#x60;write&#x60;, &#x60;review&#x60;, no expiry) and returns the token string - the only time it is ever shown. Emits the event &#x60;device.paired&#x60;.  Edge cases: - **Device id reuse** (contracts/sync/SYNC.md 3.3). A device id is the node id of that   replica\&#39;s HLC, so it may be reused only by the same install with its local database   intact (its &#x60;hlc_last&#x60; never went backwards). Clients MUST mint a new UUIDv7   &#x60;device_id&#x60; on first launch, after a reinstall, after clearing app data and after   restoring the local database from a backup, and MUST send their existing id only when   re-pairing an intact install (typically after the device was revoked, or its token   was lost). The server cannot detect every violation; it checks what it can (next item). - **&#x60;hlc_last&#x60; check** (optional field). A client re-pairing an existing &#x60;device_id&#x60;   SHOULD send its persisted &#x60;sync_state.hlc_last&#x60;. Inside the pairing transaction, if   &#x60;hlc_last&#x60; is present and is below (byte-wise, SYNC.md 3.1) the greatest &#x60;change.hlc&#x60;   logged with &#x60;device_id&#x60; &#x3D; this device, the local database went backwards (typically   an Android Auto Backup restore, or a copied database) and reusing the id could repeat   HLCs: &#x60;422 validation_failed&#x60; with &#x60;errors[0].pointer &#x3D; /hlc_last&#x60;,   &#x60;errors[0].code &#x3D; hlc_regressed&#x60;. The transaction is rolled back, so the code is   **not** consumed; the client mints a new UUIDv7 &#x60;device_id&#x60;, resets its replica state   as for a new device, and pairs again with the same code. &#x60;hlc_last&#x60; whose device part   is not &#x60;device_id&#x60; is also &#x60;422 validation_failed&#x60; (&#x60;code &#x3D; invalid_value&#x60;). Equal to   the greatest logged HLC is fine (it is the last op the device minted). Absent, or a   &#x60;device_id&#x60; that has never logged an op: no check. The server reads the maximum under   advisory lock &#x60;7033001&#x60;, so a concurrent push cannot slip between the check and the commit. - &#x60;device_id&#x60; already exists and the request re-pairs that intact install: the row is   reused, &#x60;name&#x60;, &#x60;kind&#x60;, &#x60;push_topic&#x60; are overwritten, &#x60;revoked_at&#x60; is cleared,   &#x60;paired_at&#x60; is set to now. Ops the device pushed earlier stay; its unpushed outbox can   now be pushed. - &#x60;device_id&#x60; is the server device id: &#x60;422 validation_failed&#x60;. - Both checks above run before the code is consumed; on &#x60;422&#x60; the code stays valid. - Code unknown, expired or used: &#x60;401 invalid_pairing_code&#x60;. More than 10 failures from   one client address in 15 minutes: &#x60;429 rate_limited&#x60;. - Two requests race with one code: exactly one succeeds. 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { PairDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const api = new DevicesApi();

  const body = {
    // PairRequest
    pairRequest: {"code":"K7Q2M9XD","device_id":"01931f3e-0000-7000-8000-0000000000a1","kind":"android","name":"Pixel 9","push_topic":"https://ntfy.example.net/up-kotobaya-a1b2c3"},
  } satisfies PairDeviceRequest;

  try {
    const data = await api.pairDevice(body);
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
| **pairRequest** | [PairRequest](PairRequest.md) |  | |

### Return type

[**PairResponse**](PairResponse.md)

### Authorization

No authorization required

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **201** | Paired. Store &#x60;token&#x60; securely; it cannot be retrieved again. |  -  |
| **401** | Missing, unknown, expired or revoked credential; or wrong login or pairing data. |  -  |
| **422** | Invalid body, the server device id, or &#x60;hlc_last&#x60; below what this device id has already logged. |  -  |
| **429** | Too many attempts. |  * Retry-After - Seconds until the next attempt is allowed. <br>  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## revokeDevice

> revokeDevice(deviceId)

Revoke a device and all its tokens.

Sets &#x60;device.revoked_at&#x60; and &#x60;api_token.revoked_at&#x60; for every token with this &#x60;device_id&#x60; in one transaction. The row is kept (ops reference it). Ops already accepted stay; ops the device has not pushed yet are lost unless the same intact install is paired again with the same &#x60;device_id&#x60; (&#x60;POST /devices/pair&#x60;). Idempotent: revoking a revoked device returns &#x60;204&#x60;. The server device cannot be revoked: &#x60;409 not_applicable&#x60;. Emits the event &#x60;device.revoked&#x60;. 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { RevokeDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  const body = {
    // string | Device id.
    deviceId: 01931f3e-0000-7000-8000-0000000000a1,
  } satisfies RevokeDeviceRequest;

  try {
    const data = await api.revokeDevice(body);
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
| **deviceId** | `string` | Device id. | [Defaults to `undefined`] |

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
| **204** | Revoked. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateDevice

> Device updateDevice(deviceId, devicePatch)

Rename a device or change its push topic.

Direct write to the system table &#x60;device&#x60; (not learning data, no op). Admin only; a device changes its own row through &#x60;PATCH /devices/self&#x60;. The server device and revoked devices cannot be patched: &#x60;409 not_applicable&#x60;. 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { UpdateDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  const body = {
    // string | Device id.
    deviceId: 01931f3e-0000-7000-8000-0000000000a1,
    // DevicePatch
    devicePatch: {"push_topic":null},
  } satisfies UpdateDeviceRequest;

  try {
    const data = await api.updateDevice(body);
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
| **deviceId** | `string` | Device id. | [Defaults to `undefined`] |
| **devicePatch** | [DevicePatch](DevicePatch.md) |  | |

### Return type

[**Device**](Device.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated device. |  -  |
| **404** | No such resource. |  -  |
| **409** | The request is valid but conflicts with the current state. &#x60;code&#x60; says which rule (see the error table). |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateOwnDevice

> Device updateOwnDevice(devicePatch)

Rename the caller\&#39;s device or change its push topic.

Same body and effect as &#x60;PATCH /devices/{device_id}&#x60;, applied to the device the calling token is bound to; this is how the phone registers its UnifiedPush endpoint. Needs only &#x60;write&#x60;. Credentials not bound to a device get &#x60;403 device_required&#x60;. Direct write to the system table &#x60;device&#x60; (not learning data, no op). 

### Example

```ts
import {
  Configuration,
  DevicesApi,
} from '@kotobaya/api-client';
import type { UpdateOwnDeviceRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new DevicesApi(config);

  const body = {
    // DevicePatch
    devicePatch: {"push_topic":"https://ntfy.example.net/up-kotobaya-a1b2c3"},
  } satisfies UpdateOwnDeviceRequest;

  try {
    const data = await api.updateOwnDevice(body);
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
| **devicePatch** | [DevicePatch](DevicePatch.md) |  | |

### Return type

[**Device**](Device.md)

### Authorization

[bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | The updated device. |  -  |
| **403** | The credential is valid but may not do this. |  -  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

