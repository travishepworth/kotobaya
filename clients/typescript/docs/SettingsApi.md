# SettingsApi

All URIs are relative to */api/v1*

| Method | HTTP request | Description |
|------------- | ------------- | -------------|
| [**listSettings**](SettingsApi.md#listsettings) | **GET** /settings | All stored settings. |
| [**updateSettings**](SettingsApi.md#updatesettings) | **PATCH** /settings | Set one or more settings. |



## listSettings

> SettingList listSettings()

All stored settings.

Returns one entry per &#x60;app.settings&#x60; row whose value is not JSON &#x60;null&#x60;, ordered by &#x60;key&#x60;. Keys never written are absent: their defaults live in the shared core, not on the server. Well-known keys are listed in &#x60;contracts/db/SCHEMA.md&#x60; section 6; unknown keys are stored and returned untouched. 

### Example

```ts
import {
  Configuration,
  SettingsApi,
} from '@kotobaya/api-client';
import type { ListSettingsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new SettingsApi(config);

  try {
    const data = await api.listSettings();
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

[**SettingList**](SettingList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: Not defined
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | Stored settings. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


## updateSettings

> SettingList updateSettings(settingsPatch)

Set one or more settings.

Server emits one &#x60;settings.set&#x60; op per key in &#x60;values&#x60;, in ascending key order, all in one transaction. A JSON &#x60;null&#x60; value resets the key to its default (the row is kept with value &#x60;null&#x60; so the reset wins against older writes). Keys must match &#x60;^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$&#x60;. Values of well-known keys are validated against the types in SCHEMA.md section 6 (&#x60;422&#x60; with &#x60;pointer&#x60; &#x60;/values/&lt;key&gt;&#x60;); values of unknown keys are accepted as any JSON. An empty &#x60;values&#x60; object is &#x60;422&#x60;. The response is the full list as &#x60;GET /settings&#x60; returns it after the change. 

### Example

```ts
import {
  Configuration,
  SettingsApi,
} from '@kotobaya/api-client';
import type { UpdateSettingsRequest } from '@kotobaya/api-client';

async function example() {
  console.log("🚀 Testing @kotobaya/api-client SDK...");
  const config = new Configuration({ 
    // To configure API key authorization: sessionCookie
    apiKey: "YOUR API KEY",
    // Configure HTTP bearer authorization: bearerAuth
    accessToken: "YOUR BEARER TOKEN",
  });
  const api = new SettingsApi(config);

  const body = {
    // SettingsPatch
    settingsPatch: {"values":{"scheduler.leech_threshold":null}},
  } satisfies UpdateSettingsRequest;

  try {
    const data = await api.updateSettings(body);
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
| **settingsPatch** | [SettingsPatch](SettingsPatch.md) |  | |

### Return type

[**SettingList**](SettingList.md)

### Authorization

[sessionCookie](../README.md#sessionCookie), [bearerAuth](../README.md#bearerAuth)

### HTTP request headers

- **Content-Type**: `application/json`
- **Accept**: `application/json`, `application/problem+json`


### HTTP response details
| Status code | Description | Response headers |
|-------------|-------------|------------------|
| **200** | All stored settings after the change. |  * Kotobaya-Seq -  <br>  |
| **422** | The request is well-formed but invalid. &#x60;errors&#x60; lists every problem found. |  -  |
| **0** | Any other error. See the error table in the API description. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)

